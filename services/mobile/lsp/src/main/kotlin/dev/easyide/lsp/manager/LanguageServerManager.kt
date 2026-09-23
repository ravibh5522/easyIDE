package dev.easyide.lsp.manager

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.diagnostics.DiagnosticStore
import dev.easyide.lsp.docs.DocSnapshot
import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.session.EnvironmentNotReadyException
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.session.ServerConfig
import dev.easyide.lsp.session.ServerConfigSource
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionEvent
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import dev.easyide.lsp.workspace.FileUri
import dev.easyide.lsp.workspace.PathMapper
import dev.easyide.lsp.workspace.WorkspaceEditApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns every language-server session (lsp-lifecycle.md sec 2-3; arch.md's `ServerSupervisor`).
 *
 * Sessions are keyed `(environment, project, serverId)` and started lazily when a document of
 * one of their languages opens. Starts pass admission (`lsp.maxServers`,
 * `lsp.globalMemoryBudgetMb`); a sampler enforces per-server and global budgets through the
 * one [MemoryPolicy] kill order, as does [onMemoryPressure].
 *
 * Manager state lives on a serial dispatcher; the public entry points only enqueue onto it,
 * so they are safe from any thread, including main.
 */
class LanguageServerManager(
    private val configs: ServerConfigSource,
    private val deps: ManagerDeps,
    parent: CoroutineScope,
) {
    private val serial = deps.sessionDispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job) + serial)
    private val projects = ConcurrentHashMap<ProjectKey, ProjectContext>()
    private val statusFlow = MutableStateFlow<Map<ServerKey, ServerStatus>>(emptyMap())
    private val hooks = CopyOnWriteArrayList<MemoryPressureHook>()

    // Manager-dispatcher confined:
    private val rssKb = HashMap<ServerKey, Long>()
    private val overBudgetSamples = HashMap<ServerKey, Int>()
    private val paused = HashSet<ServerKey>()
    private val probing = HashSet<ServerKey>()
    private val restartAfterStop = HashSet<ServerKey>()
    private val stateWatchers = HashMap<ServerKey, Job>()

    /** Every server's status, for the status bar and when-clause keys. */
    val statuses: StateFlow<Map<ServerKey, ServerStatus>> = statusFlow.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(LspPolicy.MEMORY_SAMPLE_MS)
                sampleMemory()
            }
        }
    }

    fun documentStore(environmentId: String, projectId: String): DocumentStore = context(environmentId, projectId).store

    fun diagnostics(environmentId: String, projectId: String): DiagnosticStore = context(environmentId, projectId).diagnostics

    fun pathMapper(environmentId: String, projectId: String): PathMapper = context(environmentId, projectId).mapper

    fun editApplier(environmentId: String, projectId: String): WorkspaceEditApplier = context(environmentId, projectId).applier

    /**
     * Enabled sessions for [languageId], ready ones first, then `priority` descending, then
     * config order (lsp-features.md 1.1). Callers filter by `supports(feature)`.
     */
    fun sessionsFor(environmentId: String, projectId: String, languageId: String): List<LspSession> {
        val all = projects[ProjectKey(environmentId, projectId)]?.sessions?.values ?: return emptyList()
        return all.filter { languageId in it.config.value.languages && it.config.value.enabled }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<LspSession>> { if (it.value.state.value.isReady) 0 else 1 }
                .thenByDescending { it.value.config.value.priority }
                .thenBy { it.index })
            .map { it.value }
    }

    fun session(key: ServerKey): LspSession? = projects[ProjectKey(key.environmentId, key.projectId)]?.sessions?.get(key.serverId)

    /** A document of [languageId] needs its servers (the store also triggers this on open). */
    fun ensureStarted(environmentId: String, projectId: String, languageId: String) {
        val ctx = context(environmentId, projectId)
        scope.launch { needLanguage(ctx, languageId) }
    }

    /** Closes the project's documents; its servers go Idle and stay warm until their idle timer. */
    fun releaseProject(environmentId: String, projectId: String) {
        val ctx = projects[ProjectKey(environmentId, projectId)] ?: return
        scope.launch {
            ctx.store.closeAll()
            ctx.sessions.values.forEach { it.fire(SessionEvent.ReleaseProject) }
        }
    }

    /** User "restart": from Failed directly; from a live state by stopping first. */
    fun restart(key: ServerKey) {
        scope.launch {
            val s = session(key) ?: return@launch
            when (s.state.value) {
                is SessionState.Failed -> s.fire(SessionEvent.UserRestart)
                is SessionState.Stopped -> startByUser(s)
                else -> {
                    restartAfterStop += key
                    s.fire(SessionEvent.UserStop)
                }
            }
        }
    }

    fun stop(key: ServerKey) {
        scope.launch { session(key)?.fire(SessionEvent.UserStop) }
    }

    fun start(key: ServerKey) {
        scope.launch { session(key)?.let(::startByUser) }
    }

    /** Re-probes a NOT_INSTALLED server (after the pack's install finished, or user "retry"). */
    fun retryProbe(key: ServerKey) {
        scope.launch { session(key)?.let { probe(it) } }
    }

    fun onVisibleUrisChanged(environmentId: String, projectId: String, visible: Set<String>, focused: String?) {
        val ctx = context(environmentId, projectId)
        scope.launch {
            ctx.visible = visible.mapTo(HashSet(), FileUri::canonical)
            val f = focused?.let(FileUri::canonical)
            val changed = f != ctx.focused
            ctx.focused = f
            // Focusing a document is a "next need" for servers evicted earlier.
            val lang = f?.let { ctx.store.snapshot(it)?.languageId }
            if (changed && lang != null) needLanguage(ctx, lang)
        }
    }

    fun onMemoryPressure(pressure: MemoryPressure) {
        scope.launch { evict(pressure.steps) { false } }
    }

    fun registerMemoryPressureHook(hook: MemoryPressureHook) {
        hooks += hook
    }

    /** Stops every server and ends the manager. */
    suspend fun close() {
        withContext(serial) {
            projects.values.flatMap { it.sessions.values }.forEach { it.dispose() }
        }
        scope.cancel()
    }

    // ---- projects and configuration ------------------------------------------------------

    private fun context(environmentId: String, projectId: String): ProjectContext {
        val key = ProjectKey(environmentId, projectId)
        projects[key]?.let { return it }
        val created = ProjectContext(key, deps)
        val winner = projects.putIfAbsent(key, created) ?: created
        if (winner === created) {
            scope.launch { configs.serversFor(environmentId, projectId).collect { applyConfigs(created, it) } }
            scope.launch { created.store.snapshots.collect { onDocuments(created, it) } }
        }
        return winner
    }

    private fun applyConfigs(ctx: ProjectContext, list: List<ServerConfig>) {
        val old = ctx.sessions
        val next = LinkedHashMap<String, LspSession>()
        for (config in list) {
            val existing = old[config.serverId]
            if (existing == null) {
                next[config.serverId] = createSession(ctx, config)
                continue
            }
            val previous = existing.config.value
            existing.updateConfig(config)
            publishStatus(existing)
            next[config.serverId] = existing
            when {
                previous.enabled && !config.enabled -> existing.fire(SessionEvent.Disabled)
                !previous.enabled && config.enabled -> existing.fire(SessionEvent.Enabled)
                config.needsRestartComparedTo(previous) ->
                    if (existing.state.value == SessionState.NotInstalled) probe(existing) else existing.fire(SessionEvent.ConfigChanged)
            }
        }
        ctx.sessions = next
        for ((id, gone) in old) {
            if (id in next) continue
            // Stop watching first so the removed server's last states cannot re-add its status.
            stateWatchers.remove(gone.key)?.cancel()
            statusFlow.update { it - gone.key }
            scope.launch { gone.dispose() }
        }
    }

    private fun createSession(ctx: ProjectContext, config: ServerConfig): LspSession {
        val key = ServerKey(ctx.key.environmentId, ctx.key.projectId, config.serverId)
        val session = LspSession(key, config, ctx.sessionDeps, scope)
        stateWatchers[key] = scope.launch { session.state.collect { onState(ctx, session, it) } }
        if (!config.enabled) session.fire(SessionEvent.Disabled)
        return session
    }

    private fun onDocuments(ctx: ProjectContext, docs: Map<String, DocSnapshot>) {
        val opened = docs.keys - ctx.seenUris
        ctx.seenUris = docs.keys
        opened.mapNotNullTo(HashSet()) { docs[it]?.languageId }.forEach { needLanguage(ctx, it) }
    }

    private fun onState(ctx: ProjectContext, session: LspSession, state: SessionState) {
        val key = session.key
        if (state !is SessionState.Stopped && state !is SessionState.Failed && state !is SessionState.NotInstalled) paused -= key
        if (!state.isLive) {
            rssKb -= key
            overBudgetSamples -= key
        }
        publishStatus(session)
        if (state == SessionState.NotInstalled && hasEligibleDocs(ctx, session)) probe(session)
        if (state is SessionState.Stopped) {
            if (restartAfterStop.remove(key) && state.reason == StopReason.USER) {
                startByUser(session)
                return
            }
            // Fresh or reconfigured servers start as soon as documents need them; evicted and
            // user-stopped ones wait for the next need (lsp-lifecycle.md 1.2).
            val auto = state.reason == StopReason.NEVER_STARTED || state.reason == StopReason.CONFIG_CHANGED
            if (auto && hasEligibleDocs(ctx, session)) needSession(ctx, session)
        }
    }

    private fun publishStatus(session: LspSession) {
        val key = session.key
        statusFlow.update {
            it + (key to ServerStatus(key, session.config.value.languages, session.state.value, key in paused, rssKb[key]))
        }
    }

    // ---- starting ------------------------------------------------------------------------

    private fun needLanguage(ctx: ProjectContext, languageId: String) {
        ctx.sessions.values
            .filter { languageId in it.config.value.languages && it.config.value.enabled }
            .forEach { needSession(ctx, it) }
    }

    private fun needSession(ctx: ProjectContext, session: LspSession) {
        when (val state = session.state.value) {
            SessionState.NotInstalled -> probe(session)
            is SessionState.Stopped -> if (state.reason != StopReason.USER) {
                scope.launch {
                    if (!eligibleByRootMarkers(ctx, session.config.value)) return@launch
                    if (admit(session)) session.fire(SessionEvent.DocNeeded)
                }
            }
            else -> Unit
        }
    }

    private fun startByUser(session: LspSession) {
        if (admit(session)) session.fire(SessionEvent.UserStart)
    }

    /** `command -v argv[0]` in the guest; a success moves NotInstalled -> Stopped(NEVER_STARTED). */
    private fun probe(session: LspSession) {
        if (session.state.value != SessionState.NotInstalled || !probing.add(session.key)) return
        scope.launch {
            val installed = try {
                withContext(deps.ioDispatcher) { deps.launcher.isInstalled(session.key, session.config.value.command.first()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnvironmentNotReadyException) {
                false
            } catch (e: Exception) {
                // The probe is a sandbox spawn: any failure leaves the server NOT_INSTALLED.
                deps.logSink.append(session.key, "install probe failed: ${e.message}")
                false
            } finally {
                probing -= session.key
            }
            // Stopped(NEVER_STARTED) then starts through onState when documents need it.
            if (installed) session.fire(SessionEvent.ProbeOk) else deps.logSink.append(session.key, "not installed: ${session.config.value.command.first()}")
        }
    }

    /**
     * v1 always roots at `/workspace` (arch.md 7.8); markers only decide eligibility. They are
     * checked at the project root: nested roots are open issue O4.
     */
    private suspend fun eligibleByRootMarkers(ctx: ProjectContext, config: ServerConfig): Boolean {
        val markers = config.rootMarkers
        if (markers.isEmpty() || DEFAULT_ROOT_MARKER in markers) return true
        val dir = deps.locator.projectDir(ctx.key.environmentId, ctx.key.projectId)
        return withContext(deps.ioDispatcher) { markers.any { File(dir, it).exists() } }
    }

    private fun hasEligibleDocs(ctx: ProjectContext, session: LspSession): Boolean {
        val languages = session.config.value.languages
        return ctx.store.snapshots.value.values.any { it.languageId in languages }
    }

    /**
     * Admission before a start (lsp-lifecycle.md 2.2): fits `lsp.maxServers` and the global
     * budget by projected budgets, evicting idle and not-visible servers if needed. Refused
     * when only focused servers could make room: the session stays stopped, "paused (memory)".
     */
    private fun admit(candidate: LspSession): Boolean {
        val settings = deps.settings.value
        val live = allSessions().filter { it !== candidate && it.state.value.isLive }.toMutableList()
        fun fits() = live.size < settings.maxServers &&
            live.sumOf { it.config.value.memoryBudgetMb } + candidate.config.value.memoryBudgetMb <= settings.globalMemoryBudgetMb
        if (!fits()) {
            for (victim in victims(setOf(EvictionStep.IDLE, EvictionStep.NOT_VISIBLE))) {
                val s = session(victim) ?: continue
                if (s === candidate) continue
                s.fire(SessionEvent.Evict)
                live.remove(s)
                if (fits()) break
            }
        }
        val admitted = fits()
        if (admitted) paused -= candidate.key else paused += candidate.key
        publishStatus(candidate)
        return admitted
    }

    // ---- memory ----------------------------------------------------------------------------

    /** One sampler tick; `internal` so tests drive it without waiting for the period. */
    internal suspend fun sampleMemory() {
        withContext(serial) {
            for (s in allSessions().filter { it.state.value.isLive }) {
                val handle = s.processHandle() ?: continue
                val kb = withContext(deps.ioDispatcher) { deps.memoryProbe.rssKb(s.key, handle) } ?: continue
                rssKb[s.key] = kb
                val budgetKb = s.config.value.memoryBudgetMb * LspPolicy.KB_PER_MB
                val over = if (kb > budgetKb) (overBudgetSamples[s.key] ?: 0) + 1 else 0
                if (over >= LspPolicy.OVER_BUDGET_SAMPLES) {
                    overBudgetSamples -= s.key
                    s.fire(SessionEvent.OverBudget)
                } else {
                    overBudgetSamples[s.key] = over
                }
                publishStatus(s)
            }
            val globalKb = deps.settings.value.globalMemoryBudgetMb * LspPolicy.KB_PER_MB
            var sum = rssKb.values.sum()
            if (sum > globalKb) {
                val target = globalKb * LspPolicy.EVICT_TARGET_RATIO
                evict(EvictionStep.entries.toSet()) { victim ->
                    sum -= rssKb[victim] ?: 0
                    sum <= target
                }
            }
        }
    }

    /** Walks the kill order over [steps]; [enough] sees each stopped key and ends the walk when true. */
    private suspend fun evict(steps: Set<EvictionStep>, enough: (ServerKey) -> Boolean) {
        for (victim in MemoryPolicy.evictionOrder(views()).filter { it.step in steps }) {
            when (victim) {
                Victim.PressureHooks -> hooks.forEach { it.onMemoryPressure() }
                is Victim.Session -> {
                    session(victim.key)?.fire(SessionEvent.Evict) ?: continue
                    if (enough(victim.key)) return
                }
            }
        }
    }

    private fun victims(steps: Set<EvictionStep>): List<ServerKey> =
        MemoryPolicy.evictionOrder(views()).filterIsInstance<Victim.Session>().filter { it.step in steps }.map { it.key }

    private fun views(): List<SessionView> = projects.values.flatMap { ctx ->
        ctx.sessions.values.map { s ->
            val open = s.openUris
            SessionView(
                key = s.key,
                state = s.state.value,
                lastUsedAt = s.lastUsedAt,
                rssKb = rssKb[s.key],
                hasVisibleDoc = open.any { it in ctx.visible },
                servesFocused = ctx.focused?.let { it in open } == true,
            )
        }
    }

    private fun allSessions(): List<LspSession> = projects.values.flatMap { it.sessions.values }

    private companion object {
        const val DEFAULT_ROOT_MARKER = ".git"
    }
}
