package dev.easyide.lsp.manager

import dev.easyide.lsp.session.AfterStop
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason

/**
 * What the status bar and Extension Log show for one server (LSP-11).
 *
 * @property pausedForMemory admission refused a start: status "paused (memory)".
 * @property rssKb last sampled resident size of the process tree, when known.
 */
data class ServerStatus(
    val key: ServerKey,
    val languages: Set<String>,
    val state: SessionState,
    val pausedForMemory: Boolean,
    val rssKb: Long?,
)

/** `lspState:<lang>` values (sdk-reference when-clause context). */
enum class LspStateValue(val wire: String) {
    OFF("off"), STARTING("starting"), READY("ready"), OVER_BUDGET("overBudget"), CRASHED("crashed");

    companion object {
        /** lsp-lifecycle.md 1.4. */
        fun of(state: SessionState): LspStateValue = when (state) {
            SessionState.NotInstalled -> OFF
            is SessionState.Stopped -> if (state.reason == StopReason.EVICTED) OVER_BUDGET else OFF
            SessionState.Starting, SessionState.Initializing, is SessionState.Backoff -> STARTING
            SessionState.Running, is SessionState.Idle -> READY
            is SessionState.Stopping -> when (val then = state.then) {
                // A stop in flight reports where it is heading.
                is AfterStop.Fail -> failed(then.reason)
                is AfterStop.Stop -> if (then.reason == StopReason.EVICTED) OVER_BUDGET else OFF
                AfterStop.Restart -> STARTING
            }
            is SessionState.Failed -> failed(state.reason)
        }

        private fun failed(reason: FailReason): LspStateValue = when (reason) {
            FailReason.Disabled -> OFF
            FailReason.OverBudget -> OVER_BUDGET
            else -> CRASHED
        }

        /** Best of several servers for one language: ready > starting > overBudget > crashed > off. */
        fun best(values: Collection<LspStateValue>): LspStateValue =
            PRECEDENCE.firstOrNull { it in values } ?: OFF

        private val PRECEDENCE = listOf(READY, STARTING, OVER_BUDGET, CRASHED, OFF)
    }
}
