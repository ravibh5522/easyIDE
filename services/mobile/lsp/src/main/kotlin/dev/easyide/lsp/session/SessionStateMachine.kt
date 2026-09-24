package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy

enum class StopReason { NEVER_STARTED, IDLE_TIMEOUT, EVICTED, USER, CONFIG_CHANGED }

sealed interface FailReason {
    data class SpawnFailed(val message: String) : FailReason
    data object InitTimeout : FailReason
    data class ProtocolError(val detail: String) : FailReason
    data object CrashLoop : FailReason
    data object OverBudget : FailReason
    data object EnvironmentNotReady : FailReason
    data object Disabled : FailReason
}

/** What a [SessionState.Stopping] turns into once the process is gone. */
sealed interface AfterStop {
    data class Stop(val reason: StopReason) : AfterStop
    data class Fail(val reason: FailReason) : AfterStop

    /** The one over-budget restart (lsp-lifecycle.md 1.2). */
    data object Restart : AfterStop
}

/**
 * Server lifecycle (lsp-lifecycle.md 1.1). [Stopping] carries its outcome so "Stopping ->
 * Stopped(reason) / Failed(reason) per the triggering row" needs no side table.
 */
sealed interface SessionState {
    data object NotInstalled : SessionState
    data class Stopped(val reason: StopReason) : SessionState
    data object Starting : SessionState
    data object Initializing : SessionState
    data object Running : SessionState
    data class Idle(val since: Long) : SessionState
    data class Stopping(val then: AfterStop) : SessionState
    data class Backoff(val attempt: Int, val retryAt: Long) : SessionState
    data class Failed(val reason: FailReason, val stderrTail: List<String>) : SessionState

    /** Counts toward `lsp.maxServers` (lsp-lifecycle.md 2.2). */
    val isLive: Boolean get() = this is Starting || this is Initializing || this is Running || this is Idle || this is Backoff

    /** Has an initialized server that can answer requests. */
    val isReady: Boolean get() = this is Running || this is Idle
}

sealed interface SessionEvent {
    data object ProbeOk : SessionEvent
    data object DocNeeded : SessionEvent
    data object UserStart : SessionEvent
    data object SpawnOk : SessionEvent
    data class SpawnFailed(val reason: FailReason) : SessionEvent
    data object InitOk : SessionEvent

    /** `initialize` answered but unusable (error, or a position encoding we did not offer). */
    data class InitRejected(val detail: String) : SessionEvent
    data object InitTimeout : SessionEvent

    /** Process exit or transport closed, not requested by us. */
    data class Crashed(val detail: String) : SessionEvent
    data class ProtocolError(val detail: String) : SessionEvent
    data object LastDocClosed : SessionEvent
    data object DocOpened : SessionEvent
    data object IdleTimer : SessionEvent
    data object Evict : SessionEvent
    data object OverBudget : SessionEvent
    data object ConfigChanged : SessionEvent
    data object UserStop : SessionEvent
    data object ReleaseProject : SessionEvent
    data object StopFinished : SessionEvent
    data object RetryTimer : SessionEvent
    data object UserRestart : SessionEvent
    data object Disabled : SessionEvent
    data object Enabled : SessionEvent
}

/** Side effects the session driver performs after a transition, in list order. */
sealed interface Effect {
    data object Spawn : Effect
    data object Initialize : Effect

    /** `initialized`, configuration, `didOpen` of every eligible document. */
    data object Activate : Effect

    /** Stop the current process now (no `shutdown` handshake). */
    data object Kill : Effect

    /** `shutdown` / `exit` / terminate / kill, then [SessionEvent.StopFinished]. */
    data object Shutdown : Effect
    data object CloseAllDocs : Effect
    data object RecordCrash : Effect
    data object ResetHistory : Effect
    data object MarkBudgetRestart : Effect
}

data class Transition(val to: SessionState, val effects: List<Effect> = emptyList())

/**
 * Inputs a transition may depend on besides (state, event).
 *
 * @property crashesInWindow crashes within `LspPolicy.CRASH_WINDOW_MS` before this event.
 * @property budgetRestartUsed whether this session already used its one over-budget restart.
 */
data class TransitionContext(
    val now: Long,
    val crashesInWindow: Int,
    val maxRetries: Int,
    val backoffMs: Long,
    val budgetRestartUsed: Boolean,
    val stderrTail: List<String>,
)

/**
 * The transition table of lsp-lifecycle.md 1.2 as a pure function, so every row is testable
 * without processes or time. Returns null for a pair the table does not list: the driver
 * ignores and logs it.
 *
 * Where two rows overlap the more specific one wins: `Backoff` + config change / user stop
 * goes straight to `Stopped` (there is no process to stop).
 */
object SessionStateMachine {

    fun next(state: SessionState, event: SessionEvent, ctx: TransitionContext): Transition? = when (event) {
        SessionEvent.ProbeOk -> on(state is SessionState.NotInstalled) { Transition(SessionState.Stopped(StopReason.NEVER_STARTED)) }
        SessionEvent.DocNeeded -> on(state is SessionState.Stopped && state.reason != StopReason.USER) { spawn() }
        SessionEvent.UserStart -> on(state == SessionState.Stopped(StopReason.USER)) { spawn() }
        SessionEvent.SpawnOk -> on(state is SessionState.Starting) { Transition(SessionState.Initializing, listOf(Effect.Initialize)) }
        is SessionEvent.SpawnFailed -> on(state is SessionState.Starting) { Transition(SessionState.Failed(event.reason, ctx.stderrTail)) }
        SessionEvent.InitOk -> on(state is SessionState.Initializing) { Transition(SessionState.Running, listOf(Effect.Activate)) }
        SessionEvent.InitTimeout -> on(state is SessionState.Initializing) {
            Transition(SessionState.Failed(FailReason.InitTimeout, ctx.stderrTail), listOf(Effect.Kill))
        }
        is SessionEvent.InitRejected -> on(state is SessionState.Initializing) {
            Transition(SessionState.Failed(FailReason.ProtocolError(event.detail), ctx.stderrTail), listOf(Effect.Kill))
        }
        is SessionEvent.Crashed, is SessionEvent.ProtocolError -> on(state is SessionState.Initializing || state.isReady) { crash(ctx) }
        SessionEvent.LastDocClosed -> on(state is SessionState.Running) { Transition(SessionState.Idle(ctx.now)) }
        SessionEvent.DocOpened -> on(state is SessionState.Idle) { Transition(SessionState.Running) }
        SessionEvent.IdleTimer -> on(state is SessionState.Idle) { stop(AfterStop.Stop(StopReason.IDLE_TIMEOUT)) }
        SessionEvent.Evict -> on(state.isReady) { stop(AfterStop.Stop(StopReason.EVICTED)) }
        SessionEvent.OverBudget -> on(state.isReady) {
            if (ctx.budgetRestartUsed) {
                stop(AfterStop.Fail(FailReason.OverBudget))
            } else {
                Transition(SessionState.Stopping(AfterStop.Restart), listOf(Effect.MarkBudgetRestart, Effect.Shutdown))
            }
        }
        SessionEvent.ConfigChanged -> stopOrCancel(state, StopReason.CONFIG_CHANGED)
        SessionEvent.UserStop -> stopOrCancel(state, StopReason.USER)
        SessionEvent.ReleaseProject -> on(state.isReady) {
            // An Idle session keeps its original `since`, so release does not extend its life.
            Transition(if (state is SessionState.Idle) state else SessionState.Idle(ctx.now), listOf(Effect.CloseAllDocs))
        }
        SessionEvent.StopFinished -> (state as? SessionState.Stopping)?.let { stopping ->
            when (val then = stopping.then) {
                is AfterStop.Stop -> Transition(SessionState.Stopped(then.reason))
                is AfterStop.Fail -> Transition(SessionState.Failed(then.reason, ctx.stderrTail))
                AfterStop.Restart -> spawn()
            }
        }
        SessionEvent.RetryTimer -> on(state is SessionState.Backoff) { spawn() }
        SessionEvent.UserRestart -> on(state is SessionState.Failed && state.reason != FailReason.Disabled) {
            Transition(SessionState.Starting, listOf(Effect.ResetHistory, Effect.Spawn))
        }
        SessionEvent.Disabled -> disable(state)
        SessionEvent.Enabled -> on(state is SessionState.Failed && state.reason == FailReason.Disabled) {
            Transition(SessionState.Stopped(StopReason.NEVER_STARTED))
        }
    }

    /** `backoffMs * 2^(n-1)`, with the exponent capped so a large `maxRetries` cannot overflow. */
    fun backoffDelayMs(backoffMs: Long, attempt: Int): Long =
        backoffMs * (1L shl (attempt - 1).coerceIn(0, LspPolicy.MAX_BACKOFF_DOUBLINGS))

    private inline fun on(guard: Boolean, transition: () -> Transition): Transition? = if (guard) transition() else null

    private fun spawn() = Transition(SessionState.Starting, listOf(Effect.Spawn))

    private fun stop(then: AfterStop) = Transition(SessionState.Stopping(then), listOf(Effect.Shutdown))

    private fun crash(ctx: TransitionContext): Transition {
        val n = ctx.crashesInWindow + 1
        val effects = listOf(Effect.RecordCrash, Effect.Kill)
        return if (n > ctx.maxRetries) {
            Transition(SessionState.Failed(FailReason.CrashLoop, ctx.stderrTail), effects)
        } else {
            Transition(SessionState.Backoff(n, ctx.now + backoffDelayMs(ctx.backoffMs, n)), effects)
        }
    }

    private fun stopOrCancel(state: SessionState, reason: StopReason): Transition? = when {
        state.isReady -> stop(AfterStop.Stop(reason))
        state is SessionState.Backoff -> Transition(SessionState.Stopped(reason))
        else -> null
    }

    private fun disable(state: SessionState): Transition? = when (state) {
        SessionState.Starting, SessionState.Initializing, SessionState.Running, is SessionState.Idle -> stop(AfterStop.Fail(FailReason.Disabled))
        // Already stopping: keep the shutdown in flight, only change where it ends.
        is SessionState.Stopping -> Transition(SessionState.Stopping(AfterStop.Fail(FailReason.Disabled)))
        is SessionState.Failed -> if (state.reason == FailReason.Disabled) null else Transition(SessionState.Failed(FailReason.Disabled, emptyList()))
        SessionState.NotInstalled, is SessionState.Stopped, is SessionState.Backoff -> Transition(SessionState.Failed(FailReason.Disabled, emptyList()))
    }
}
