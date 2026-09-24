package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.TraceLevel
import dev.easyide.lsp.docs.DocSnapshot
import dev.easyide.lsp.json.putOpt
import dev.easyide.lsp.jsonrpc.ConnectionClosedException
import dev.easyide.lsp.jsonrpc.ErrorCodes
import dev.easyide.lsp.jsonrpc.RpcOutcome
import dev.easyide.lsp.jsonrpc.RpcTracer
import dev.easyide.lsp.jsonrpc.isCancellation
import dev.easyide.lsp.protocol.ClientCapabilitiesBuilder
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.LspMethod
import dev.easyide.lsp.protocol.ServerCapabilities
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A result and the document version it was computed for (lsp-features.md 3.2 staleness). */
data class Versioned<R>(val value: R, val uri: String?, val version: Int?)

/** A server error answer that is not a cancellation; user-initiated actions show [message]. */
class LspRequestException(val code: Int, message: String) : Exception(message)

/**
 * One language server for one (environment, project, server) key, across restarts
 * (lsp-client.md sec 6, lsp-lifecycle.md sec 1).
 *
 * All mutable state is confined to a serial dispatcher (`limitedParallelism(1)`); public
 * suspend functions hop onto it, so no locks are needed and the enqueue order into the
 * connection's single writer is the wire order - which is how a request flushes its document
 * before it is sent. Lifecycle events are applied through [SessionStateMachine]; this class
 * only performs the effects.
 */
class LspSession internal constructor(
    val key: ServerKey,
    initialConfig: ServerConfig,
    private val deps: SessionDeps,
    parent: CoroutineScope,
) {
    private val serial = deps.sessionDispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job) + serial)

    private val configState = MutableStateFlow(initialConfig)
    private val stateFlow = MutableStateFlow<SessionState>(SessionState.NotInstalled)
    private val capabilitiesFlow = MutableStateFlow<ServerCapabilities?>(null)
    private val registrationsFlow = MutableStateFlow<Map<String, Registration>>(emptyMap())
    private val progressFlow = MutableStateFlow<Map<String, WorkDoneProgress>>(emptyMap())
    private val oversizedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val refreshFlow = MutableSharedFlow<RefreshKind>(extraBufferCapacity = EVENT_BUFFER)
    private val changesFlow = MutableSharedFlow<SyncedChange>(extraBufferCapacity = EVENT_BUFFER)

    val config: StateFlow<ServerConfig> = configState.asStateFlow()
    val state: StateFlow<SessionState> = stateFlow.asStateFlow()

    /** Null unless a process has completed `initialize`. */
    val capabilities: StateFlow<ServerCapabilities?> = capabilitiesFlow.asStateFlow()
    val registrations: StateFlow<Map<String, Registration>> = registrationsFlow.asStateFlow()
    val progress: StateFlow<Map<String, WorkDoneProgress>> = progressFlow.asStateFlow()

    /** Documents too large to sync to this Full-sync server (the editor says features are off). */
    val oversizedUris: StateFlow<Set<String>> = oversizedFlow.asStateFlow()
    val refreshes: SharedFlow<RefreshKind> = refreshFlow.asSharedFlow()

    /** Every change the server received, with its [dev.easyide.lsp.text.EditDelta] for decoration shifting. */
    val changes: SharedFlow<SyncedChange> = changesFlow.asSharedFlow()

    /** Session log ring: stderr, `window/logMessage`, transitions, timeouts, trace. */
    val log = LogRing(LspPolicy.LOG_RING_LINES)
    private val stderrTail = LogRing(LspPolicy.STDERR_TAIL_LINES)

    /** Last request or didChange, for LRU eviction. */
    @Volatile var lastUsedAt: Long = deps.clock.nowMs()
        private set

    /** Uris this server currently has open; read by the manager for eviction order. */
    @Volatile var openUris: Set<String> = emptySet()
        private set

    @Volatile private var instance: ServerInstance? = null
    private val advertised = ClientCapabilitiesBuilder.advertisedFeatures(deps.client.milestone, deps.client.ui)
    private val crashes = CrashHistory(LspPolicy.CRASH_WINDOW_MS)
    private var budgetRestartUsed = false
    private var generation = 0
    private var spawnJob: Job? = null
    private var timerJob: Job? = null
    private var flushJob: Job? = null
    private val pullJobs = HashMap<String, Job>()
    private val pullResultIds = HashMap<String, String>()

    private val tracer = object : RpcTracer {
        override val level get() = deps.settings.value.trace
        override fun record(line: String) = logLine(line)
    }

    /**
     * True when this session is ready and [feature] is advertised by the client at this
     * milestone, allowed by the server's `features` filter, and offered by the server.
     */
    fun supports(feature: LspFeature): Boolean {
        val caps = capabilitiesFlow.value ?: return false
        return stateFlow.value.isReady && feature in advertised && configState.value.features.allows(feature) && caps.supports(feature)
    }

    /**
     * Sends a typed request. With a [uri] the document is flushed first and the result is
     * stamped with the version the server saw.
     *
     * @return null when the session is not ready, the feature is unsupported, the document is
     *   not synced to this server, or the call timed out / was cancelled / hit content-modified.
     * @throws LspRequestException for any other server error.
     */
    suspend fun <R> request(method: LspMethod<R>, params: JsonElement, uri: String?): Versioned<R>? = withContext(serial) {
        val inst = instance?.takeIf { stateFlow.value.isReady } ?: return@withContext null
        val feature = method.feature
        if (feature != null && !supports(feature)) return@withContext null
        val canonical = uri?.let(FileUri::canonical)
        var version: Int? = null
        if (canonical != null) {
            val sync = inst.sync ?: return@withContext null
            // Catch up with the store first: the snapshot collector may not have run since the
            // editor's last change, and the request must see the text the user sees.
            onSnapshots(inst, deps.store.snapshots.value)
            sync.flush(canonical)?.let { published(inst, listOf(it)) }
            version = sync.versionOf(canonical) ?: return@withContext null
        }
        lastUsedAt = deps.clock.nowMs()
        val timeout = LspPolicy.timeoutFor(method.name, deps.settings.value.requestTimeoutMs)
        val outcome = try {
            inst.connection.request(method.name, params, timeout)
        } catch (e: ConnectionClosedException) {
            return@withContext null
        }
        when (outcome) {
            RpcOutcome.Timeout -> null
            is RpcOutcome.Error -> {
                val error = outcome.error
                if (error.isCancellation || error.code == ErrorCodes.CONTENT_MODIFIED) null else throw LspRequestException(error.code, error.message)
            }
            is RpcOutcome.Result -> Versioned(method.decode(outcome.value), canonical, version)
        }
    }

    /** Sends a notification in order with every other message of this session; dropped when no server runs. */
    suspend fun notify(method: String, params: JsonElement?) = withContext(serial) {
        instance?.takeIf { stateFlow.value.isReady }?.connection?.notify(method, params)
    }

    /** Applies a lifecycle event asynchronously on the session dispatcher. */
    internal fun fire(event: SessionEvent) {
        scope.launch { apply(event, null) }
    }

    internal fun updateConfig(config: ServerConfig) {
        configState.value = config
    }

    internal fun processHandle(): ServerProcessHandle? = instance?.process

    /** Adds a line to this session's log (and the Extension Log), e.g. a pipeline's swallowed error. */
    fun record(line: String) = logLine(line)

    /** Kills the process and ends the session for good (manager disposal). */
    internal suspend fun dispose() {
        withContext(serial) { teardown() }
        scope.cancel()
    }

    // ---- lifecycle driver -------------------------------------------------------------

    private fun apply(event: SessionEvent, gen: Int?) {
        // Instance-bound events from a process that has since been replaced are stale.
        if (gen != null && gen != generation) return
        val from = stateFlow.value
        val now = deps.clock.nowMs()
        val settings = deps.settings.value
        val ctx = TransitionContext(now, crashes.count(now), settings.restartMaxRetries, settings.restartBackoffMs, budgetRestartUsed, stderrTail.snapshot())
        val transition = SessionStateMachine.next(from, event, ctx)
        if (transition == null) {
            logLine("ignored $event in $from")
            return
        }
        if (transition.to != from) {
            // Observers treat Running as "requests may be sent now" and route by capability,
            // so capabilities must be visible before the state flips or an immediate request
            // finds no capable server.
            if (Effect.Activate in transition.effects) instance?.capabilities?.let { capabilitiesFlow.value = it }
            stateFlow.value = transition.to
            logLine("state $from -> ${transition.to} ($event)")
            armTimer(transition.to)
        }
        transition.effects.forEach(::perform)
    }

    private fun armTimer(to: SessionState) {
        timerJob?.cancel()
        timerJob = when (to) {
            is SessionState.Idle -> scope.launch {
                delay(configState.value.idleShutdownSec * MS_PER_SEC)
                apply(SessionEvent.IdleTimer, null)
            }
            is SessionState.Backoff -> scope.launch {
                delay((to.retryAt - deps.clock.nowMs()).coerceAtLeast(0))
                apply(SessionEvent.RetryTimer, null)
            }
            else -> null
        }
    }

    private fun perform(effect: Effect) {
        when (effect) {
            Effect.Spawn -> spawn()
            Effect.Initialize -> initialize()
            Effect.Activate -> activate()
            Effect.Kill -> teardown()
            Effect.Shutdown -> shutdown()
            Effect.CloseAllDocs -> instance?.let { inst -> inst.sync?.let { it.closeAll(); afterSync(inst, it, emptySet()) } }
            Effect.RecordCrash -> crashes.record(deps.clock.nowMs())
            Effect.ResetHistory -> {
                crashes.clear()
                budgetRestartUsed = false
            }
            Effect.MarkBudgetRestart -> budgetRestartUsed = true
        }
    }

    private fun spawn() {
        val gen = ++generation
        stderrTail.clear()
        val cfg = configState.value
        spawnJob = scope.launch {
            val outer = coroutineContext.job
            val handle = try {
                // The process must never be lost: if this job is cancelled while the launcher
                // runs, the finished spawn is killed here instead of leaking.
                withContext(NonCancellable) {
                    val h = deps.launcher.launch(key, cfg.command, cfg.env)
                    if (gen != generation || !outer.isActive) {
                        h.kill()
                        null
                    } else {
                        h
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnvironmentNotReadyException) {
                logLine("spawn refused: ${e.message}")
                apply(SessionEvent.SpawnFailed(FailReason.EnvironmentNotReady), gen)
                return@launch
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                logLine("spawn failed: $message")
                apply(SessionEvent.SpawnFailed(FailReason.SpawnFailed(message)), gen)
                return@launch
            } ?: return@launch
            val inst = ServerInstance(
                generation = gen,
                process = handle,
                parent = scope,
                serial = serial,
                io = deps.ioDispatcher,
                handler = serverRequests(),
                tracer = tracer,
                onStderr = { line ->
                    stderrTail.add(line)
                    logLine("stderr: $line")
                },
                onEnded = { event -> scope.launch { apply(event, gen) } },
            )
            instance = inst
            inst.start()
            apply(SessionEvent.SpawnOk, gen)
        }
    }

    private fun initialize() {
        val inst = instance ?: return
        val timeoutMs = configState.value.startupTimeoutSec * MS_PER_SEC
        inst.trace = deps.settings.value.trace
        inst.scope.launch {
            when (val outcome = inst.initialize(initializeParams(inst.trace), timeoutMs)) {
                is InitOutcome.Ok -> {
                    inst.capabilities = outcome.capabilities
                    apply(SessionEvent.InitOk, inst.generation)
                }
                is InitOutcome.Rejected -> apply(SessionEvent.InitRejected(outcome.detail), inst.generation)
                InitOutcome.TimedOut -> apply(SessionEvent.InitTimeout, inst.generation)
                InitOutcome.Closed -> Unit
            }
        }
    }

    private fun initializeParams(trace: TraceLevel): JsonObject {
        val cfg = configState.value
        return buildJsonObject {
            // The app's pid means nothing inside the guest.
            put("processId", JsonNull)
            put("clientInfo", buildJsonObject {
                put("name", JsonPrimitive(deps.client.name))
                put("version", JsonPrimitive(deps.client.version))
            })
            put("locale", JsonPrimitive(deps.client.locale))
            put("rootUri", JsonPrimitive(deps.mapper.rootUri))
            putOpt("rootPath", FileUri.toPath(deps.mapper.rootUri))
            put("workspaceFolders", JsonArray(listOf(workspaceFolderJson(deps.mapper, deps.workspaceName))))
            put("capabilities", ClientCapabilitiesBuilder.build(deps.client.milestone, deps.client.ui))
            putOpt("initializationOptions", cfg.initializationOptions)
            put("trace", JsonPrimitive(trace.wire))
        }
    }

    private fun activate() {
        val inst = instance ?: return
        val caps = inst.capabilities ?: return
        capabilitiesFlow.value = caps
        inst.connection.notify(METHOD_INITIALIZED, JsonObject(emptyMap()))
        val sync = DocumentSync(caps.sync, inst.connection::notify) { snap -> snap.languageId in configState.value.languages }
        inst.sync = sync
        SessionCollectors.start(inst, key, deps, configState, onSnapshots = { onSnapshots(inst, it) })
        onSnapshots(inst, deps.store.snapshots.value)
        if (sync.eligibleCount == 0) scope.launch { apply(SessionEvent.LastDocClosed, null) }
    }

    private fun onSnapshots(inst: ServerInstance, snapshots: Map<String, DocSnapshot>) {
        if (instance !== inst) return
        val sync = inst.sync ?: return
        val before = sync.eligibleCount
        val openedBefore = sync.syncedUris()
        val sent = sync.reconcile(snapshots)
        published(inst, sent)
        afterSync(inst, sync, openedBefore)
        if (sync.syncedUris().any { it !in openedBefore } || sent.isNotEmpty()) lastUsedAt = deps.clock.nowMs()
        armFlush(inst)
        val after = sync.eligibleCount
        if (before > 0 && after == 0) scope.launch { apply(SessionEvent.LastDocClosed, null) }
        if (before == 0 && after > 0) scope.launch { apply(SessionEvent.DocOpened, null) }
    }

    /** Book-keeping after the synced set may have changed: views, pulls for new and closed docs. */
    private fun afterSync(inst: ServerInstance, sync: DocumentSync, openedBefore: Set<String>) {
        val now = sync.syncedUris()
        openUris = now
        oversizedFlow.value = sync.oversized.toSet()
        for (uri in openedBefore - now) {
            pullJobs.remove(uri)?.cancel()
            pullResultIds.remove(uri)
            if (usesPull()) deps.diagnostics.put(uri, key, null, emptyList())
        }
        for (uri in now - openedBefore) schedulePull(inst, uri)
    }

    private fun armFlush(inst: ServerInstance) {
        flushJob?.cancel()
        flushJob = inst.scope.launch {
            delay(deps.settings.value.didChangeDebounceMs)
            val sync = inst.sync ?: return@launch
            val sent = sync.flushAll()
            if (sent.isNotEmpty()) lastUsedAt = deps.clock.nowMs()
            published(inst, sent)
        }
    }

    private fun published(inst: ServerInstance, sent: List<SyncedChange>) {
        for (change in sent) {
            changesFlow.tryEmit(change)
            schedulePull(inst, change.uri)
        }
    }

    private fun usesPull(): Boolean =
        capabilitiesFlow.value?.pullDiagnostics == true && configState.value.features.allows(LspFeature.DIAGNOSTICS)

    private fun schedulePull(inst: ServerInstance, uri: String) {
        if (!usesPull()) return
        pullJobs.remove(uri)?.cancel()
        pullJobs[uri] = inst.scope.launch {
            delay(LspPolicy.PULL_DIAGNOSTICS_DEBOUNCE_MS)
            DiagnosticPull.run(inst, key, uri, deps, pullResultIds)
        }
    }

    private fun serverRequests() = ServerRequests(
        key = key,
        workspaceName = deps.workspaceName,
        mapper = deps.mapper,
        configuration = deps.configuration,
        ui = deps.ui,
        edits = deps.edits,
        diagnostics = deps.diagnostics,
        log = ::logLine,
        usesPull = ::usesPull,
        onDiagnosticsRefresh = { instance?.let { inst -> openUris.forEach { schedulePull(inst, it) } } },
        registrations = registrationsFlow,
        progress = progressFlow,
        refreshes = refreshFlow,
    )

    private fun shutdown() {
        val inst = instance
        val gen = generation
        spawnJob?.cancel()
        scope.launch {
            inst?.shutdown(LspPolicy.SHUTDOWN_GRACE_MS)
            teardown()
            apply(SessionEvent.StopFinished, gen)
        }
    }

    /** Drops the current process and everything derived from it. Idempotent. */
    private fun teardown() {
        spawnJob?.cancel()
        flushJob?.cancel()
        pullJobs.values.forEach { it.cancel() }
        pullJobs.clear()
        pullResultIds.clear()
        instance?.dispose()
        instance = null
        capabilitiesFlow.value = null
        registrationsFlow.value = emptyMap()
        progressFlow.value = emptyMap()
        oversizedFlow.value = emptySet()
        openUris = emptySet()
        deps.diagnostics.clearServer(key)
    }

    private fun logLine(line: String) {
        log.add(line)
        deps.logSink.append(key, line)
    }

    private companion object {
        const val METHOD_INITIALIZED = "initialized"
        const val MS_PER_SEC = 1000L
        const val EVENT_BUFFER = 64
    }
}
