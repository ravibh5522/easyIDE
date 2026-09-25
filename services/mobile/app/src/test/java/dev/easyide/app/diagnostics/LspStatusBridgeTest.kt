package dev.easyide.app.diagnostics

import dev.easyide.lsp.manager.LspStateValue
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.AfterStop
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LspStatusBridgeTest {
    private val key = ServerKey("env1", "proj1", "acme.py/pyright")
    private val other = ServerKey("env1", "proj1", "acme.ts/tsserver")

    private fun status(k: ServerKey, state: SessionState, paused: Boolean = false) =
        ServerStatus(k, setOf("python"), state, paused, rssKb = null)

    private fun map(vararg statuses: ServerStatus) = statuses.associateBy { it.key }

    @Test fun `a new server is a change`() {
        val changes = LspStatusBridge.changes(emptyMap(), map(status(key, SessionState.Starting)))
        assertEquals(listOf(key), changes.map { it.status.key })
        assertEquals(LspStateValue.STARTING, changes.single().mark.state)
    }

    @Test fun `an unchanged mark is not a change even when the raw state moved`() {
        val known = mapOf(key to LspStatusBridge.mark(status(key, SessionState.Running)))
        assertEquals(emptyList<LspChange>(), LspStatusBridge.changes(known, map(status(key, SessionState.Idle(since = 99)))))
    }

    @Test fun `backoff attempts do not each count`() {
        val known = mapOf(key to LspStatusBridge.mark(status(key, SessionState.Backoff(1, 10))))
        assertEquals(emptyList<LspChange>(), LspStatusBridge.changes(known, map(status(key, SessionState.Backoff(2, 20)))))
    }

    @Test fun `ready to crashed is a change, and so is a different failure reason`() {
        val ready = mapOf(key to LspStatusBridge.mark(status(key, SessionState.Running)))
        val crashed = status(key, SessionState.Failed(FailReason.CrashLoop, listOf("x")))
        assertEquals(1, LspStatusBridge.changes(ready, map(crashed)).size)

        val known = mapOf(key to LspStatusBridge.mark(crashed))
        val other = status(key, SessionState.Failed(FailReason.InitTimeout, emptyList()))
        assertEquals(1, LspStatusBridge.changes(known, map(other)).size)
        assertEquals(emptyList<LspChange>(), LspStatusBridge.changes(known, map(crashed)))
    }

    @Test fun `memory pause is part of the mark`() {
        val known = mapOf(key to LspStatusBridge.mark(status(key, SessionState.Stopped(StopReason.NEVER_STARTED))))
        val paused = status(key, SessionState.Stopped(StopReason.NEVER_STARTED), paused = true)
        assertEquals(1, LspStatusBridge.changes(known, map(paused)).size)
    }

    @Test fun `only the servers that changed are returned`() {
        val known = mapOf(
            key to LspStatusBridge.mark(status(key, SessionState.Running)),
            other to LspStatusBridge.mark(status(other, SessionState.Starting)),
        )
        val changes = LspStatusBridge.changes(known, map(status(key, SessionState.Running), status(other, SessionState.Running)))
        assertEquals(listOf(other), changes.map { it.status.key })
    }

    @Test fun `a server that disappears is not reported`() {
        val known = mapOf(key to LspStatusBridge.mark(status(key, SessionState.Running)))
        assertEquals(emptyList<LspChange>(), LspStatusBridge.changes(known, emptyMap()))
    }

    @Test fun `a stopping server that will fail already carries the failure`() {
        val mark = LspStatusBridge.mark(status(key, SessionState.Stopping(AfterStop.Fail(FailReason.CrashLoop))))
        assertEquals(FailReason.CrashLoop, mark.failure)
        assertNull(LspStatusBridge.mark(status(key, SessionState.Stopping(AfterStop.Restart))).failure)
    }

    @Test fun `a healthy change is one info line`() {
        val change = LspStatusBridge.changes(emptyMap(), map(status(key, SessionState.Running))).single()
        assertEquals(listOf(LogLevel.INFO to "$key ready"), LspStatusBridge.lines(change, listOf("ignored")))
    }

    @Test fun `a crash is an error line followed by the last five log lines`() {
        val state = SessionState.Failed(FailReason.SpawnFailed("no such file"), emptyList())
        val change = LspStatusBridge.changes(emptyMap(), map(status(key, state))).single()
        val tail = (1..8).map { "log $it" }
        val lines = LspStatusBridge.lines(change, tail)
        assertEquals(LogLevel.ERROR to "$key crashed: spawn failed: no such file", lines.first())
        assertEquals(listOf("  log 4", "  log 5", "  log 6", "  log 7", "  log 8"), lines.drop(1).map { it.second })
        assertEquals(setOf(LogLevel.ERROR), lines.map { it.first }.toSet())
    }

    @Test fun `going over budget is a warning`() {
        val change = LspStatusBridge.changes(emptyMap(), map(status(key, SessionState.Failed(FailReason.OverBudget, emptyList())))).single()
        assertEquals(LogLevel.WARN to "$key overBudget: over memory budget", LspStatusBridge.lines(change, emptyList()).single())
    }

    @Test fun `a memory pause is noted in the header`() {
        val change = LspStatusBridge.changes(emptyMap(), map(status(key, SessionState.Stopped(StopReason.NEVER_STARTED), paused = true))).single()
        assertEquals(LogLevel.WARN to "$key off (paused: memory)", LspStatusBridge.lines(change, emptyList()).single())
    }

    @Test fun `every failure reason has a description`() {
        val reasons = listOf(
            FailReason.SpawnFailed("m"), FailReason.InitTimeout, FailReason.ProtocolError("p"), FailReason.CrashLoop,
            FailReason.OverBudget, FailReason.EnvironmentNotReady, FailReason.Disabled,
        )
        assertEquals(reasons.size, reasons.map(LspStatusBridge::describe).toSet().size)
    }

    @Test fun `the flow is logged per change, with the session log preferred for a failure`() = runTest {
        val statuses = MutableStateFlow(map(status(key, SessionState.Starting)))
        val sink = ListSink()
        LspStatusBridge.start(statuses, { listOf("from session") }, sink, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        statuses.value = map(status(key, SessionState.Idle(1)))
        advanceUntilIdle()
        statuses.value = map(status(key, SessionState.Failed(FailReason.CrashLoop, listOf("from state"))))
        advanceUntilIdle()
        statuses.value = map(status(key, SessionState.Failed(FailReason.CrashLoop, listOf("from state"))))
        advanceUntilIdle()

        assertEquals(
            listOf(
                ListSink.Entry(LogLevel.INFO, LogSource.LSP, "$key starting"),
                ListSink.Entry(LogLevel.INFO, LogSource.LSP, "$key ready"),
                ListSink.Entry(LogLevel.ERROR, LogSource.LSP, "$key crashed: crash loop"),
                ListSink.Entry(LogLevel.ERROR, LogSource.LSP, "  from session"),
            ),
            sink.entries,
        )
    }

    @Test fun `the failed state's own stderr tail is used when the session has no log`() = runTest {
        val statuses = MutableStateFlow(map(status(key, SessionState.Failed(FailReason.CrashLoop, listOf("stderr line")))))
        val sink = ListSink()
        LspStatusBridge.start(statuses, { emptyList() }, sink, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf("$key crashed: crash loop", "  stderr line"), sink.entries.map { it.message })
    }
}
