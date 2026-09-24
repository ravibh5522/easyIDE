package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.lsp.manager.LspStateValue
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.AfterStop
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LspStatusTest {

    private val key = ServerKey("e", "p", "s")
    private fun kind(state: SessionState, paused: Boolean = false) = ServerStatusKind.of(ServerStatus(key, setOf("python"), state, paused, null))

    @Test
    fun everyStateHasOneKind() {
        assertEquals(ServerStatusKind.NOT_INSTALLED, kind(SessionState.NotInstalled))
        assertEquals(ServerStatusKind.STARTING, kind(SessionState.Starting))
        assertEquals(ServerStatusKind.STARTING, kind(SessionState.Initializing))
        assertEquals(ServerStatusKind.STARTING, kind(SessionState.Backoff(1, 0)))
        assertEquals(ServerStatusKind.READY, kind(SessionState.Running))
        assertEquals(ServerStatusKind.READY, kind(SessionState.Idle(0)))
        assertEquals(ServerStatusKind.STOPPED, kind(SessionState.Stopped(StopReason.USER)))
        assertEquals(ServerStatusKind.PAUSED_MEMORY, kind(SessionState.Stopped(StopReason.EVICTED)))
        assertEquals(ServerStatusKind.PAUSED_MEMORY, kind(SessionState.Stopped(StopReason.NEVER_STARTED), paused = true))
        assertEquals(ServerStatusKind.STARTING, kind(SessionState.Stopping(AfterStop.Restart)))
        assertEquals(ServerStatusKind.OVER_BUDGET, kind(SessionState.Stopping(AfterStop.Fail(FailReason.OverBudget))))
        assertEquals(ServerStatusKind.CRASHED, kind(SessionState.Failed(FailReason.CrashLoop, listOf("Traceback"))))
        assertEquals(ServerStatusKind.DISABLED, kind(SessionState.Failed(FailReason.Disabled, emptyList())))
        assertEquals(ServerStatusKind.ENVIRONMENT_NOT_READY, kind(SessionState.Failed(FailReason.EnvironmentNotReady, emptyList())))
    }

    @Test
    fun summaryShowsTheMostActionable() {
        assertEquals(ServerStatusKind.CRASHED, ServerStatusKind.summary(listOf(ServerStatusKind.READY, ServerStatusKind.CRASHED)))
        assertEquals(ServerStatusKind.READY, ServerStatusKind.summary(listOf(ServerStatusKind.READY, ServerStatusKind.STOPPED)))
        assertEquals(null, ServerStatusKind.summary(emptyList()))
        assertTrue(ServerStatusKind.CRASHED.canRestart)
        assertFalse(ServerStatusKind.NOT_INSTALLED.canRestart)
    }

    @Test
    fun failureDetailAndTail() {
        val failed = ServerStatus(key, setOf("python"), SessionState.Failed(FailReason.SpawnFailed("no proot"), listOf("a", "b")), false, null)
        assertEquals("no proot", failed.failureDetail())
        assertEquals(listOf("a", "b"), failed.stderrTail())
    }

    @Test
    fun languageFactsCombineTheServersOfEachLanguage() {
        val pyright = ServerStatus(ServerKey("e", "p", "pyright"), setOf("python"), SessionState.Running, false, null)
        val ruff = ServerStatus(ServerKey("e", "p", "ruff"), setOf("python", "toml"), SessionState.Failed(FailReason.CrashLoop, emptyList()), false, null)
        val offers = mapOf(pyright.key to setOf(LspFeature.HOVER, LspFeature.COMPLETION), ruff.key to setOf(LspFeature.FORMATTING))
        val facts = LspLanguageFacts.of(listOf(pyright, ruff)) { k, f -> f in offers.getValue(k) }
        val python = facts.getValue("python")
        assertEquals(LspStateValue.READY, python.state)
        assertTrue(python.ready)
        assertEquals(setOf(LspFeature.HOVER, LspFeature.COMPLETION, LspFeature.FORMATTING), python.features)
        val toml = facts.getValue("toml")
        assertEquals(LspStateValue.CRASHED, toml.state)
        assertFalse(toml.ready)
        assertTrue(LspLanguageFacts.of(emptyList()) { _, _ -> true }.isEmpty())
    }
}
