package dev.easyide.app.extensions.registry

import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.extensions.install.RegistryOrigin
import dev.easyide.app.extensions.install.StageResult
import dev.easyide.app.extensions.install.StagedPackage
import dev.easyide.extensions.AppApi
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.PublisherKeys
import dev.easyide.extensions.registry.RegistryVerifier
import dev.easyide.extensions.registry.Revocations
import dev.easyide.extensions.registry.Verified
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException

/** A registry package validated in staging, waiting on the capability sheet. */
data class RegistryStaged(
    val pkg: StagedPackage,
    val registryId: String,
    val entry: IndexEntry,
    /** The publisher keyId that signed the entry; pinned when the install commits. */
    val signedBy: String,
    /** Installed from the package cache with no download. */
    val fromCache: Boolean,
)

sealed interface RegistryPrepare {
    data class Ready(val staged: RegistryStaged) : RegistryPrepare
    data class Failed(val error: RegistryError) : RegistryPrepare
}

/**
 * The registry install pipeline (registry-and-install.md sec 8) in front of [LocalInstaller]:
 * resolve the entry -> package from the cache or downloaded (`.partial`, then renamed) ->
 * `checkBytes` (size, sha256) -> `trustEntry` (publisher key, revocation, TOFU pin or
 * rotation chain) -> the local stage/validate -> capability sheet -> commit with
 * [Source.REGISTRY] and the signing keyId -> pin. Every failure is a hard stop with its reason.
 *
 * The checked bytes are the ones unpacked (held in memory, capped by
 * `extensions.limits.packageMb`), so nothing can replace the file between check and unpack.
 */
class RegistryInstaller(
    private val client: RegistryClient,
    private val cache: PackageCache,
    private val verifier: RegistryVerifier,
    private val trust: TrustStore,
    private val installer: LocalInstaller,
    private val maxPackageBytes: () -> Long,
    private val io: CoroutineDispatcher,
    private val apiVersion: SemVer = AppApi.VERSION,
) {
    /**
     * Everything up to the capability sheet. [allowNetwork] false (tests, or a device known
     * offline) uses only the cache; true still prefers cached, verified data and touches the
     * network only for what is missing.
     */
    suspend fun prepare(config: RegistryConfig, requested: IndexEntry, allowNetwork: Boolean = true): RegistryPrepare {
        val cached = withContext(io) { client.cached(config) }
            ?: return fail(RegistryError.Network("${config.id}: no verified index; refresh first"))
        // Resolved again against the current verified copy: a refresh may have replaced it.
        val entry = cached.index.entries.firstOrNull { it.id == requested.id && it.version == requested.version }
            ?: return fail(RegistryError.Rejected("${requested.id} ${requested.version} is not in the verified ${config.id} index"))
        val revocations = cached.index.revocations
        if (!entry.engines.contains(apiVersion)) {
            return fail(RegistryError.Rejected("${entry.id} ${entry.version} requires easyIDE API ${entry.engines.raw}; this app provides $apiVersion"))
        }
        revocations.reason(entry.id, entry.version, entry.signature.keyId)?.let { return fail(RegistryError.Rejected("${entry.id} ${entry.version}: $it")) }

        val bytes = when (val o = withContext(io) { cache.obtain(entry, maxPackageBytes()) { verifier.checkBytes(entry, it) } }) {
            is PackageCache.Obtained.Failed -> return fail(o.error)
            is PackageCache.Obtained.Ok -> o
        }
        val signedBy = when (val t = withContext(io) { trusted(config, entry, revocations, allowNetwork) }) {
            is RegistryResult.Failed -> return fail(t.error)
            is RegistryResult.Ok -> t.value
        }
        val pkg = when (val s = installer.stageArchive { ByteArrayInputStream(bytes.bytes) }) {
            is StageResult.Rejected -> return fail(RegistryError.Rejected(s.problems.joinToString("\n")))
            is StageResult.Staged -> s.pkg
        }
        mismatch(entry, pkg)?.let {
            installer.discard(pkg)
            return fail(RegistryError.Rejected(it))
        }
        return RegistryPrepare.Ready(RegistryStaged(pkg, config.id, entry, signedBy, bytes.fromCache))
    }

    /**
     * Commits an approved [staged] package, then pins its signer. Refused when a refresh
     * since [prepare] revoked it. @throws IOException when a filesystem step fails.
     */
    suspend fun commit(config: RegistryConfig, staged: RegistryStaged, envId: String?) {
        val e = staged.entry
        val revoked = withContext(io) { client.cached(config) }?.index?.revocations?.reason(e.id, e.version, staged.signedBy)
        if (revoked != null) {
            installer.discard(staged.pkg)
            throw IOException("${e.id} ${e.version}: $revoked")
        }
        installer.commit(staged.pkg, envId, Source.REGISTRY, RegistryOrigin(staged.registryId, staged.signedBy, e.sha256))
        withContext(io) { trust.pin(staged.registryId, e.publisher, staged.signedBy) }
    }

    /** Cached publisher file first; fetched when it is missing or does not establish trust (a new key or rotation). */
    private fun trusted(config: RegistryConfig, entry: IndexEntry, revocations: Revocations, allowNetwork: Boolean): RegistryResult<String> {
        val pinned = trust.pinned(config.id, entry.publisher)
        var failure: RegistryError? = null
        for (network in if (allowNetwork) listOf(false, true) else listOf(false)) {
            when (val keys = client.publisherKeys(config, entry.publisher, network)) {
                is RegistryResult.Failed -> failure = keys.error
                is RegistryResult.Ok -> when (val v = trustEntry(entry, keys.value, revocations, pinned)) {
                    is Verified.Ok -> return RegistryResult.Ok(v.value)
                    is Verified.Rejected -> failure = RegistryError.Rejected(v.reason)
                }
            }
        }
        return RegistryResult.Failed(failure ?: RegistryError.Network("${entry.publisher}: publisher keys unavailable"))
    }

    private fun trustEntry(entry: IndexEntry, keys: PublisherKeys, revocations: Revocations, pinned: String?) =
        verifier.trustEntry(entry, keys, revocations, pinned)

    /** The package must be what the signed entry says: id, version, scope and exactly its capabilities. */
    private fun mismatch(entry: IndexEntry, pkg: StagedPackage): String? {
        val d = pkg.descriptor
        if (d.id != entry.id || d.version != entry.version) return "package holds ${d.id} ${d.version}, the index entry is ${entry.id} ${entry.version}"
        if (d.scope != entry.scope) return "${entry.id}: package scope ${d.scope.wire} differs from the index (${entry.scope.wire})"
        val signed = entry.capabilities.map { Capability.parse(it)?.id ?: it }.toSet()
        val declared = d.capabilities.items.mapTo(HashSet()) { it.id }
        if (signed != declared) return "${entry.id}: package capabilities ${declared.sorted()} differ from the signed entry ${signed.sorted()}"
        return null
    }

    private fun fail(error: RegistryError) = RegistryPrepare.Failed(error)
}
