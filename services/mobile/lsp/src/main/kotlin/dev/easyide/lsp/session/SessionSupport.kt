package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.docs.DocSnapshot
import dev.easyide.lsp.jsonrpc.ConnectionClosedException
import dev.easyide.lsp.jsonrpc.RpcOutcome
import dev.easyide.lsp.protocol.DocumentDiagnosticReport
import dev.easyide.lsp.protocol.LspMethods
import dev.easyide.lsp.protocol.LspParams
import dev.easyide.lsp.workspace.PathMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Crash timestamps within a sliding window (lsp-lifecycle.md 1.3). Session-dispatcher confined. */
internal class CrashHistory(private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    /**
     * Crashes within the window before [now]. Pruning here is what makes "a session that stays
     * Running for a full window clears its history" hold without a timer.
     */
    fun count(now: Long): Int {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        return times.size
    }

    fun record(now: Long) {
        times.addLast(now)
    }

    fun clear() = times.clear()
}

/** The one workspace folder, identical in `initialize` and `workspace/workspaceFolders` (LSP-12). */
internal fun workspaceFolderJson(mapper: PathMapper, name: String): JsonObject = buildJsonObject {
    put("uri", JsonPrimitive(mapper.rootUri))
    put("name", JsonPrimitive(name))
}

/** Long-running collectors of one activated process; they die with the instance scope. */
internal object SessionCollectors {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        inst: ServerInstance,
        key: ServerKey,
        deps: SessionDeps,
        config: StateFlow<ServerConfig>,
        onSnapshots: (Map<String, DocSnapshot>) -> Unit,
    ) {
        // settingsSection value -> didChangeConfiguration, first value included (lsp-client.md 6 step 3).
        inst.scope.launch {
            config.map { it.settingsSection }
                .distinctUntilChanged()
                .flatMapLatest { section -> if (section == null) emptyFlow() else deps.configuration.section(key, section) }
                .collect { value ->
                    inst.connection.notify(METHOD_DID_CHANGE_CONFIGURATION, buildJsonObject { put("settings", value) })
                }
        }
        // Compared with what `initialize` sent, not with the first value seen: a change made
        // before this collector starts must still reach the server.
        inst.scope.launch {
            deps.settings.map { it.trace }.distinctUntilChanged().collect { trace ->
                if (trace == inst.trace) return@collect
                inst.trace = trace
                inst.connection.notify(METHOD_SET_TRACE, buildJsonObject { put("value", JsonPrimitive(trace.wire)) })
            }
        }
        // A change of `languages` changes eligibility, so it re-runs reconciliation too. The
        // emission is only a trigger: `combine` buffers, and a request may already have
        // reconciled a newer map, so the store's current value is what gets reconciled.
        inst.scope.launch {
            combine(deps.store.snapshots, config.map { it.languages }.distinctUntilChanged()) { _, _ -> Unit }
                .collect { onSnapshots(deps.store.snapshots.value) }
        }
    }

    private const val METHOD_DID_CHANGE_CONFIGURATION = "workspace/didChangeConfiguration"
    private const val METHOD_SET_TRACE = "\$/setTrace"
}

/** Pull diagnostics for one document (lsp-features.md 4.1). */
internal object DiagnosticPull {

    /**
     * Pulls [uri] at the version the server has now; `unchanged` keeps the previous list.
     * Errors and timeouts are silent: the next flush pulls again.
     */
    suspend fun run(inst: ServerInstance, key: ServerKey, uri: String, deps: SessionDeps, resultIds: MutableMap<String, String>) {
        val version = inst.sync?.versionOf(uri) ?: return
        val caps = inst.capabilities ?: return
        val method = LspMethods.PULL_DIAGNOSTICS
        val params = LspParams.pullDiagnostics(uri, caps.diagnosticIdentifier, resultIds[uri])
        val outcome = try {
            inst.connection.request(method.name, params, LspPolicy.timeoutFor(method.name, deps.settings.value.requestTimeoutMs))
        } catch (e: ConnectionClosedException) {
            return
        }
        val result = (outcome as? RpcOutcome.Result)?.value ?: return
        when (val report = method.decode(result)) {
            is DocumentDiagnosticReport.Full -> {
                deps.diagnostics.put(uri, key, version, report.items)
                val id = report.resultId
                if (id != null) resultIds[uri] = id else resultIds.remove(uri)
            }
            is DocumentDiagnosticReport.Unchanged -> resultIds[uri] = report.resultId
            null -> Unit
        }
    }
}
