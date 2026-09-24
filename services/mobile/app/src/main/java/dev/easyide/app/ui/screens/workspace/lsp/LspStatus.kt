package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.AfterStop
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason

/**
 * What the status bar says about one server (LSP-11): every [SessionState] of
 * lsp-lifecycle.md 1.1 lands on exactly one kind, and each kind has one label and one set of
 * actions in the UI.
 */
enum class ServerStatusKind {
    NOT_INSTALLED, STARTING, READY, PAUSED_MEMORY, OVER_BUDGET, CRASHED, STOPPED, DISABLED, ENVIRONMENT_NOT_READY;

    /** Restart makes sense: the server is running, stopped by the user, or failed. */
    val canRestart: Boolean get() = this != NOT_INSTALLED && this != DISABLED && this != ENVIRONMENT_NOT_READY

    val isProblem: Boolean get() = this == NOT_INSTALLED || this == CRASHED || this == OVER_BUDGET || this == ENVIRONMENT_NOT_READY

    companion object {
        fun of(status: ServerStatus): ServerStatusKind {
            if (status.pausedForMemory && !status.state.isLive) return PAUSED_MEMORY
            return when (val s = status.state) {
                SessionState.NotInstalled -> NOT_INSTALLED
                SessionState.Starting, SessionState.Initializing, is SessionState.Backoff -> STARTING
                SessionState.Running, is SessionState.Idle -> READY
                is SessionState.Stopped -> if (s.reason == StopReason.EVICTED) PAUSED_MEMORY else STOPPED
                is SessionState.Stopping -> when (val then = s.then) {
                    AfterStop.Restart -> STARTING
                    is AfterStop.Stop -> if (then.reason == StopReason.EVICTED) PAUSED_MEMORY else STOPPED
                    is AfterStop.Fail -> failed(then.reason)
                }
                is SessionState.Failed -> failed(s.reason)
            }
        }

        private fun failed(reason: FailReason): ServerStatusKind = when (reason) {
            FailReason.Disabled -> DISABLED
            FailReason.OverBudget -> OVER_BUDGET
            FailReason.EnvironmentNotReady -> ENVIRONMENT_NOT_READY
            else -> CRASHED
        }

        /** The one kind shown for several servers of a language: the most actionable first. */
        fun summary(kinds: Collection<ServerStatusKind>): ServerStatusKind? = PRECEDENCE.firstOrNull { it in kinds }

        private val PRECEDENCE = listOf(
            CRASHED, NOT_INSTALLED, ENVIRONMENT_NOT_READY, OVER_BUDGET, PAUSED_MEMORY, STARTING, READY, STOPPED, DISABLED,
        )
    }
}

/** A failed session's stderr tail, for the status menu's "why" line. */
fun ServerStatus.stderrTail(): List<String> = (state as? SessionState.Failed)?.stderrTail.orEmpty()

/** The spawn failure text when the server could not start at all. */
fun ServerStatus.failureDetail(): String? = when (val s = state) {
    is SessionState.Failed -> when (val r = s.reason) {
        is FailReason.SpawnFailed -> r.message
        is FailReason.ProtocolError -> r.detail
        else -> null
    }
    else -> null
}
