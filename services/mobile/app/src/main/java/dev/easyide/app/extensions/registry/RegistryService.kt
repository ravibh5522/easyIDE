package dev.easyide.app.extensions.registry

import dev.easyide.app.data.settings.AutoCheckUpdates
import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.ExtensionStateStore
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.extensions.AppApi
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.registry.Catalog
import dev.easyide.extensions.registry.Ed25519
import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.RegistryVerifier
import dev.easyide.extensions.registry.Revocations
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** One configured registry: its last verified index (null before the first refresh) and the last refresh error. */
data class RegistryStatus(val config: RegistryConfig, val cached: CachedIndex?, val error: RegistryError?, val refreshing: Boolean = false) {
    fun age(now: Instant): Duration? = cached?.let { Duration.between(it.fetchedAt, now).coerceAtLeast(Duration.ZERO) }

    fun stale(now: Instant): Boolean = age(now)?.let { it.toDays() >= RegistryPolicy.STALE_INDEX_WARN_DAYS } ?: false
}

/** `state.json` registry records the UI shows: TOFU pins and why installed versions are revoked. */
data class RegistryRecords(val pins: Map<String, Map<String, String>> = emptyMap(), val revoked: Map<String, String> = emptyMap())

/** What browse shows: registries with their state, and the catalog over every verified index. */
data class RegistryView(
    val configProblems: List<String>,
    val statuses: List<RegistryStatus>,
    val catalog: Catalog,
    /** The registry listing each `(id, version)`; the first configured registry wins a tie. */
    val sources: Map<Pair<ExtensionId, SemVer>, String>,
) {
    /** False means "no registry configured" (the default: there is no public index yet). */
    val configured: Boolean get() = statuses.isNotEmpty()

    fun sourceOf(entry: IndexEntry): String? = sources[entry.id to entry.version]
}

/**
 * The registry side of the Extensions screen and the app (registry-and-install.md sec 3-7,
 * 10): configured registries and their verified indexes, refresh (with revocation
 * re-evaluation: installed revoked versions are disabled with a reason, never uninstalled),
 * the install pipeline, the revocation predicate [LocalInstaller] consults, and TOFU pins.
 */
class RegistryService(
    paths: SandboxPaths,
    private val state: ExtensionStateStore,
    http: HttpFetcher,
    ed25519: Ed25519,
    installer: LocalInstaller,
    private val inventory: DiskExtensionInventory,
    maxPackageBytes: () -> Long,
    private val io: CoroutineDispatcher,
    /** Tells the user (Extension Log) about revocations found at refresh. */
    private val notify: (ExtensionId?, String) -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val apiVersion: SemVer = AppApi.VERSION,
) {
    private val verifier = RegistryVerifier(ed25519)
    val client = RegistryClient(paths, http, verifier, clock)
    val cache = PackageCache(paths, http)
    val trust = TrustStore(state)
    private val pipeline = RegistryInstaller(client, cache, verifier, trust, installer, maxPackageBytes, io, apiVersion)

    private val viewState = MutableStateFlow(RegistryView(emptyList(), emptyList(), Catalog(emptyList(), apiVersion), emptyMap()))
    val view: StateFlow<RegistryView> = viewState.asStateFlow()
    private val recordState = MutableStateFlow(RegistryRecords())
    val records: StateFlow<RegistryRecords> = recordState.asStateFlow()
    private val lock = Mutex()

    fun config(registryId: String): RegistryConfig? = viewState.value.statuses.firstOrNull { it.config.id == registryId }?.config

    /** `extensions.registries` changed (or app start): load each registry's last verified copy. */
    suspend fun setConfigs(configs: RegistryConfigs) = lock.withLock {
        val statuses = withContext(io) {
            configs.registries.map { c ->
                val old = viewState.value.statuses.firstOrNull { it.config == c }
                RegistryStatus(c, client.cached(c), old?.error)
            }
        }
        publish(configs.problems, statuses)
        reloadRecords()
    }

    /** Refreshes every configured registry; failures keep the last verified copy and are shown per registry. */
    suspend fun refreshAll() = lock.withLock {
        val current = viewState.value
        publish(current.configProblems, current.statuses.map { it.copy(refreshing = true) })
        val next = current.statuses.map { s ->
            when (val r = withContext(io) { client.refresh(s.config) }) {
                is RegistryResult.Ok -> {
                    applyRevocations(s.config.id, r.value.index.revocations)
                    withContext(io) { refreshInstalledPublishers(s.config) }
                    RegistryStatus(s.config, r.value, null)
                }
                is RegistryResult.Failed -> RegistryStatus(s.config, withContext(io) { client.cached(s.config) }, r.error)
            }.also { done -> viewState.update { v -> v.copy(statuses = v.statuses.map { if (it.config == done.config) done else it }) } }
        }
        publish(current.configProblems, next)
        withContext(io) { cache.gc(pinnedPackages()) }
        reloadRecords()
    }

    /** `extensions.autoCheckUpdates`: refresh when the last successful one is older than the period. Only notifies. */
    suspend fun refreshIfDue(mode: AutoCheckUpdates) {
        val period = when (mode) {
            AutoCheckUpdates.OFF -> return
            AutoCheckUpdates.DAILY -> RegistryPolicy.DAILY_MS
            AutoCheckUpdates.WEEKLY -> RegistryPolicy.WEEKLY_MS
        }
        val now = clock()
        val due = viewState.value.statuses.any { s -> s.cached == null || Duration.between(s.cached.fetchedAt, now).toMillis() >= period }
        if (due) refreshAll()
    }

    suspend fun prepare(registryId: String, entry: IndexEntry, allowNetwork: Boolean = true): RegistryPrepare {
        val config = config(registryId) ?: return RegistryPrepare.Failed(RegistryError.Rejected("registry '$registryId' is not configured"))
        return pipeline.prepare(config, entry, allowNetwork)
    }

    /** @throws IOException when the install cannot be committed (nothing is pinned then). */
    suspend fun commit(staged: RegistryStaged, envId: String?) {
        val config = config(staged.registryId) ?: throw IOException("registry '${staged.registryId}' is not configured")
        try {
            pipeline.commit(config, staged, envId)
        } finally {
            reloadRecords()
        }
    }

    /** [LocalInstaller]'s revocation seam: recorded revocations plus every verified index's list. */
    fun isRevoked(id: String, version: String): Boolean =
        RegistryRules.isRevoked(state.read(), revocations(), id, version)

    suspend fun forgetPin(registryId: String, publisher: String) {
        withContext(io) { trust.forget(registryId, publisher) }
        reloadRecords()
    }

    private suspend fun reloadRecords() {
        recordState.value = withContext(io) {
            val s = state.read()
            RegistryRecords(s.pins, s.revoked.values.fold(emptyMap()) { acc, m -> acc + m })
        }
    }

    private fun revocations(): Map<String, Revocations> =
        viewState.value.statuses.mapNotNull { s -> s.cached?.let { s.config.id to it.index.revocations } }.toMap()

    /**
     * Sec 6: after a refresh, installed versions [revocations] of [registryId] revokes are
     * recorded (the inventory then disables them with reason REVOKED) and the user is told.
     */
    private suspend fun applyRevocations(registryId: String, revocations: Revocations) {
        val changed = withContext(io) {
            val before = state.read()
            val now = RegistryRules.revokedInstalls(before.installs, registryId, revocations)
            val old = before.revoked[registryId].orEmpty()
            if (now == old) return@withContext false
            state.update { s -> s.copy(revoked = if (now.isEmpty()) s.revoked - registryId else s.revoked + (registryId to now)) }
            (now - old.keys).forEach { (key, reason) ->
                val id = key.substringBefore('@')
                notify(ExtensionId.parse(id), "$key disabled: revoked by registry $registryId ($reason). Uninstall it, or roll back if the previous version is not revoked.")
            }
            true
        }
        if (changed) inventory.rescan()
    }

    /** Sec 3.1: publisher files of publishers with installs are refreshed with the index; failures keep the stored copy. */
    private fun refreshInstalledPublishers(config: RegistryConfig) {
        state.read().installs.mapNotNull { it.origin?.takeIf { o -> o.registryId == config.id }?.let { _ -> it.id.substringBefore('.') } }
            .distinct().forEach { client.publisherKeys(config, it, allowNetwork = true) }
    }

    private fun pinnedPackages(): Set<String> =
        state.read().installs.flatMap { listOfNotNull(it.origin?.sha256, it.previous?.origin?.sha256) }.toSet()

    private fun publish(problems: List<String>, statuses: List<RegistryStatus>) {
        val indexes = statuses.mapNotNull { s -> s.cached?.let { s.config.id to it.index } }
        val sources = LinkedHashMap<Pair<ExtensionId, SemVer>, String>()
        indexes.forEach { (id, idx) -> idx.entries.forEach { e -> sources.putIfAbsent(e.id to e.version, id) } }
        viewState.value = RegistryView(problems, statuses, Catalog(indexes.map { it.second }, apiVersion), sources)
    }
}
