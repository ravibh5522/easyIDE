package dev.easyide.lsp.session

import dev.easyide.lsp.session.SessionEvent as E
import dev.easyide.lsp.session.SessionState as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every row of lsp-lifecycle.md 1.2, then every pair the table does not list. */
class SessionStateMachineTest {
    private val now = 10_000L
    private val tail = listOf("Traceback", "ImportError: x")
    private fun ctx(crashes: Int = 0, budgetUsed: Boolean = false) =
        TransitionContext(now, crashes, maxRetries = 3, backoffMs = 2000, budgetRestartUsed = budgetUsed, stderrTail = tail)

    private data class Row(val from: S, val event: E, val to: S, val effects: List<Effect> = emptyList(), val ctx: TransitionContext? = null)

    private val stoppingTo = { then: AfterStop -> S.Stopping(then) }

    private val rows: List<Row> by lazy {
        listOf(
            Row(S.NotInstalled, E.ProbeOk, S.Stopped(StopReason.NEVER_STARTED)),
            Row(S.Stopped(StopReason.NEVER_STARTED), E.DocNeeded, S.Starting, listOf(Effect.Spawn)),
            Row(S.Stopped(StopReason.EVICTED), E.DocNeeded, S.Starting, listOf(Effect.Spawn)),
            Row(S.Stopped(StopReason.IDLE_TIMEOUT), E.DocNeeded, S.Starting, listOf(Effect.Spawn)),
            Row(S.Stopped(StopReason.CONFIG_CHANGED), E.DocNeeded, S.Starting, listOf(Effect.Spawn)),
            Row(S.Stopped(StopReason.USER), E.UserStart, S.Starting, listOf(Effect.Spawn)),
            Row(S.Starting, E.SpawnOk, S.Initializing, listOf(Effect.Initialize)),
            Row(S.Starting, E.SpawnFailed(FailReason.SpawnFailed("no proot")), S.Failed(FailReason.SpawnFailed("no proot"), tail)),
            Row(S.Starting, E.SpawnFailed(FailReason.EnvironmentNotReady), S.Failed(FailReason.EnvironmentNotReady, tail)),
            Row(S.Initializing, E.InitOk, S.Running, listOf(Effect.Activate)),
            Row(S.Initializing, E.InitTimeout, S.Failed(FailReason.InitTimeout, tail), listOf(Effect.Kill)),
            Row(S.Initializing, E.InitRejected("utf-8"), S.Failed(FailReason.ProtocolError("utf-8"), tail), listOf(Effect.Kill)),
            Row(S.Initializing, E.Crashed("exit 1"), S.Backoff(1, now + 2000), listOf(Effect.RecordCrash, Effect.Kill)),
            Row(S.Running, E.Crashed("exit 1"), S.Backoff(2, now + 4000), listOf(Effect.RecordCrash, Effect.Kill), ctx(crashes = 1)),
            Row(S.Idle(1), E.Crashed("exit 1"), S.Backoff(3, now + 8000), listOf(Effect.RecordCrash, Effect.Kill), ctx(crashes = 2)),
            Row(S.Running, E.Crashed("exit 1"), S.Failed(FailReason.CrashLoop, tail), listOf(Effect.RecordCrash, Effect.Kill), ctx(crashes = 3)),
            Row(S.Running, E.ProtocolError("garbage"), S.Backoff(1, now + 2000), listOf(Effect.RecordCrash, Effect.Kill)),
            Row(S.Initializing, E.ProtocolError("garbage"), S.Backoff(1, now + 2000), listOf(Effect.RecordCrash, Effect.Kill)),
            Row(S.Idle(1), E.ProtocolError("garbage"), S.Failed(FailReason.CrashLoop, tail), listOf(Effect.RecordCrash, Effect.Kill), ctx(crashes = 3)),
            Row(S.Idle(1), E.OverBudget, stoppingTo(AfterStop.Restart), listOf(Effect.MarkBudgetRestart, Effect.Shutdown)),
            Row(S.Idle(1), E.UserStop, stoppingTo(AfterStop.Stop(StopReason.USER)), listOf(Effect.Shutdown)),
            Row(S.NotInstalled, E.Disabled, S.Failed(FailReason.Disabled, emptyList())),
            Row(S.Initializing, E.Disabled, stoppingTo(AfterStop.Fail(FailReason.Disabled)), listOf(Effect.Shutdown)),
            Row(S.Running, E.LastDocClosed, S.Idle(now)),
            Row(S.Idle(1), E.DocOpened, S.Running),
            Row(S.Idle(1), E.IdleTimer, stoppingTo(AfterStop.Stop(StopReason.IDLE_TIMEOUT)), listOf(Effect.Shutdown)),
            Row(S.Running, E.Evict, stoppingTo(AfterStop.Stop(StopReason.EVICTED)), listOf(Effect.Shutdown)),
            Row(S.Idle(1), E.Evict, stoppingTo(AfterStop.Stop(StopReason.EVICTED)), listOf(Effect.Shutdown)),
            Row(S.Running, E.OverBudget, stoppingTo(AfterStop.Restart), listOf(Effect.MarkBudgetRestart, Effect.Shutdown)),
            Row(S.Running, E.OverBudget, stoppingTo(AfterStop.Fail(FailReason.OverBudget)), listOf(Effect.Shutdown), ctx(budgetUsed = true)),
            Row(S.Running, E.ConfigChanged, stoppingTo(AfterStop.Stop(StopReason.CONFIG_CHANGED)), listOf(Effect.Shutdown)),
            Row(S.Idle(1), E.ConfigChanged, stoppingTo(AfterStop.Stop(StopReason.CONFIG_CHANGED)), listOf(Effect.Shutdown)),
            Row(S.Backoff(1, 5), E.ConfigChanged, S.Stopped(StopReason.CONFIG_CHANGED)),
            Row(S.Running, E.UserStop, stoppingTo(AfterStop.Stop(StopReason.USER)), listOf(Effect.Shutdown)),
            Row(S.Backoff(1, 5), E.UserStop, S.Stopped(StopReason.USER)),
            Row(S.Running, E.ReleaseProject, S.Idle(now), listOf(Effect.CloseAllDocs)),
            Row(S.Idle(1), E.ReleaseProject, S.Idle(1), listOf(Effect.CloseAllDocs)),
            Row(S.Stopping(AfterStop.Stop(StopReason.EVICTED)), E.StopFinished, S.Stopped(StopReason.EVICTED)),
            Row(S.Stopping(AfterStop.Fail(FailReason.OverBudget)), E.StopFinished, S.Failed(FailReason.OverBudget, tail)),
            Row(S.Stopping(AfterStop.Restart), E.StopFinished, S.Starting, listOf(Effect.Spawn)),
            Row(S.Backoff(1, 5), E.RetryTimer, S.Starting, listOf(Effect.Spawn)),
            Row(S.Failed(FailReason.CrashLoop, tail), E.UserRestart, S.Starting, listOf(Effect.ResetHistory, Effect.Spawn)),
            Row(S.Running, E.Disabled, stoppingTo(AfterStop.Fail(FailReason.Disabled)), listOf(Effect.Shutdown)),
            Row(S.Starting, E.Disabled, stoppingTo(AfterStop.Fail(FailReason.Disabled)), listOf(Effect.Shutdown)),
            Row(S.Stopped(StopReason.USER), E.Disabled, S.Failed(FailReason.Disabled, emptyList())),
            Row(S.Backoff(1, 5), E.Disabled, S.Failed(FailReason.Disabled, emptyList())),
            Row(S.Stopping(AfterStop.Stop(StopReason.USER)), E.Disabled, stoppingTo(AfterStop.Fail(FailReason.Disabled))),
            Row(S.Failed(FailReason.Disabled, emptyList()), E.Enabled, S.Stopped(StopReason.NEVER_STARTED)),
        )
    }

    @Test
    fun everyListedRowTransitionsAsSpecified() {
        for (row in rows) {
            val t = SessionStateMachine.next(row.from, row.event, row.ctx ?: ctx())
            assertEquals("${row.from} + ${row.event}", Transition(row.to, row.effects), t)
        }
    }

    @Test
    fun unlistedPairsAreIgnored() {
        val states = listOf(
            S.NotInstalled, S.Stopped(StopReason.NEVER_STARTED), S.Stopped(StopReason.USER), S.Starting, S.Initializing,
            S.Running, S.Idle(1), S.Stopping(AfterStop.Stop(StopReason.USER)), S.Backoff(1, 5),
            S.Failed(FailReason.CrashLoop, tail), S.Failed(FailReason.Disabled, emptyList()),
        )
        val events = listOf(
            E.ProbeOk, E.DocNeeded, E.UserStart, E.SpawnOk, E.SpawnFailed(FailReason.InitTimeout), E.InitOk, E.InitRejected("x"),
            E.InitTimeout, E.Crashed("x"), E.ProtocolError("x"), E.LastDocClosed, E.DocOpened, E.IdleTimer, E.Evict,
            E.OverBudget, E.ConfigChanged, E.UserStop, E.ReleaseProject, E.StopFinished, E.RetryTimer, E.UserRestart,
            E.Disabled, E.Enabled,
        )
        val listed = rows.map { it.from::class to it.event::class }.toSet() + DISABLED_FROM_ANY
        for (s in states) for (e in events) {
            val pair = s::class to e::class
            val special = (s == S.Stopped(StopReason.USER) && e == E.DocNeeded) ||
                (s == S.Stopped(StopReason.NEVER_STARTED) && e == E.UserStart) ||
                (s == S.Failed(FailReason.Disabled, emptyList()) && (e == E.UserRestart || e == E.Disabled)) ||
                (s == S.Failed(FailReason.CrashLoop, tail) && e == E.Enabled)
            if (pair in listed && !special) continue
            assertNull("$s + $e should be ignored", SessionStateMachine.next(s, e, ctx()))
        }
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(2000, SessionStateMachine.backoffDelayMs(2000, 1))
        assertEquals(4000, SessionStateMachine.backoffDelayMs(2000, 2))
        assertEquals(8000, SessionStateMachine.backoffDelayMs(2000, 3))
        assertEquals(SessionStateMachine.backoffDelayMs(2000, 11), SessionStateMachine.backoffDelayMs(2000, 500))
    }

    @Test
    fun crashHistoryForgetsCrashesOutsideTheWindow() {
        val h = CrashHistory(windowMs = 1000)
        h.record(0)
        h.record(500)
        assertEquals(2, h.count(900))
        assertEquals(1, h.count(1200))
        assertEquals(0, h.count(1600))
    }

    private companion object {
        // "any | server disabled": Disabled is defined from every state except Failed(Disabled).
        val DISABLED_FROM_ANY = listOf(
            S.NotInstalled::class, S.Stopped::class, S.Starting::class, S.Initializing::class, S.Running::class,
            S.Idle::class, S.Stopping::class, S.Backoff::class, S.Failed::class,
        ).map { it to E.Disabled::class }.toSet()
    }
}
