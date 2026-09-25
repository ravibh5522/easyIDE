package dev.easyide.app.diagnostics

import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.manager.LspStateValue
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.AfterStop
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * What a log line says about one server: coarse enough that idle timers and restart-attempt
 * counters do not each write a line, fine enough that a change of failure reason does.
 */
data class LspMark(val state: LspStateValue, val failure: FailReason?, val pausedForMemory: Boolean)

/** A server whose [LspMark] differs from the one last logged, with the state's own stderr tail. */
data class LspChange(val status: ServerStatus, val mark: LspMark, val stderrTail: List<String>)

/**
 * Logs one [LogSource.LSP] line per server state change (and the stderr tail of a failed
 * server), read from the manager's public status flow; the manager is not edited.
 */
object LspStatusBridge {
    /** Lines of a failed server's log appended to its failure entry. */
    const val FAILURE_TAIL_LINES = 5

    fun start(
        manager: LanguageServerManager,
        sink: LogSink,
        scope: CoroutineScope,
        context: CoroutineContext = Dispatchers.IO,
    ): Job = start(manager.statuses, { key -> manager.session(key)?.log?.snapshot().orEmpty() }, sink, scope, context)

    /**
     * @param sessionLog the running session's log for a key (empty when it has none). It is
     *   preferred over the tail captured in the failed state because it is fresher.
     */
    fun start(
        statuses: Flow<Map<ServerKey, ServerStatus>>,
        sessionLog: (ServerKey) -> List<String>,
        sink: LogSink,
        scope: CoroutineScope,
        context: CoroutineContext = Dispatchers.IO,
    ): Job = scope.launch(context) {
        var known = emptyMap<ServerKey, LspMark>()
        statuses.collect { current ->
            for (change in changes(known, current)) {
                val tail = sessionLog(change.status.key).ifEmpty { change.stderrTail }
                lines(change, tail).forEach { (level, text) -> sink.log(level, LogSource.LSP, text) }
            }
            known = current.mapValues { mark(it.value) }
        }
    }

    fun mark(status: ServerStatus): LspMark =
        LspMark(LspStateValue.of(status.state), failureOf(status.state), status.pausedForMemory)

    /**
     * The servers in [current] whose mark differs from [known]. A server that appears is a
     * change (its first state is worth a line); one that disappears is not, because its
     * removal comes with the project being released and says nothing about the server.
     */
    fun changes(known: Map<ServerKey, LspMark>, current: Map<ServerKey, ServerStatus>): List<LspChange> =
        current.values.mapNotNull { status ->
            val mark = mark(status)
            if (known[status.key] == mark) null else LspChange(status, mark, (status.state as? SessionState.Failed)?.stderrTail.orEmpty())
        }

    /** The header line, then (for a failure) up to [FAILURE_TAIL_LINES] indented tail lines at the same level. */
    fun lines(change: LspChange, tail: List<String>): List<Pair<LogLevel, String>> {
        val mark = change.mark
        val level = when {
            mark.failure != null && mark.state == LspStateValue.CRASHED -> LogLevel.ERROR
            mark.state == LspStateValue.OVER_BUDGET || mark.pausedForMemory -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        val header = buildString {
            append(change.status.key).append(' ').append(mark.state.wire)
            if (mark.pausedForMemory) append(" (paused: memory)")
            mark.failure?.let { append(": ").append(describe(it)) }
        }
        val detail = if (mark.failure == null) emptyList() else tail.takeLast(FAILURE_TAIL_LINES)
        return listOf(level to header) + detail.map { level to "  $it" }
    }

    fun describe(reason: FailReason): String = when (reason) {
        is FailReason.SpawnFailed -> "spawn failed: ${reason.message}"
        FailReason.InitTimeout -> "initialize timed out"
        is FailReason.ProtocolError -> "protocol error: ${reason.detail}"
        FailReason.CrashLoop -> "crash loop"
        FailReason.OverBudget -> "over memory budget"
        FailReason.EnvironmentNotReady -> "environment not ready"
        FailReason.Disabled -> "disabled"
    }

    private fun failureOf(state: SessionState): FailReason? = when (state) {
        is SessionState.Failed -> state.reason
        is SessionState.Stopping -> (state.then as? AfterStop.Fail)?.reason
        else -> null
    }
}
