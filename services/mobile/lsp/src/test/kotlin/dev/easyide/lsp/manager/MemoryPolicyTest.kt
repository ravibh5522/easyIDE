package dev.easyide.lsp.manager

import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryPolicyTest {
    private fun key(id: String) = ServerKey("e", "p", id)

    private fun view(id: String, state: SessionState, used: Long, rss: Long? = null, visible: Boolean = false, focused: Boolean = false) =
        SessionView(key(id), state, used, rss, visible, focused)

    @Test
    fun killOrderIsIdleLruThenHiddenLruThenHooksThenFocusedByRss() {
        val views = listOf(
            view("focused-small", SessionState.Running, used = 50, rss = 100_000, visible = true, focused = true),
            view("idle-new", SessionState.Idle(0), used = 40),
            view("hidden-old", SessionState.Running, used = 10),
            view("visible-unfocused", SessionState.Running, used = 5, visible = true),
            view("idle-old", SessionState.Idle(0), used = 20),
            view("focused-big", SessionState.Running, used = 60, rss = 900_000, visible = true, focused = true),
            view("hidden-new", SessionState.Running, used = 30),
            view("backoff", SessionState.Backoff(1, 0), used = 1),
            view("stopped", SessionState.Stopped(StopReason.USER), used = 1),
        )
        val order = MemoryPolicy.evictionOrder(views).map { v ->
            when (v) {
                is Victim.Session -> "${v.step}:${v.key.serverId}"
                Victim.PressureHooks -> "HOOKS"
            }
        }
        assertEquals(
            listOf(
                "IDLE:idle-old", "IDLE:idle-new",
                "NOT_VISIBLE:hidden-old", "NOT_VISIBLE:hidden-new",
                "HOOKS",
                "FOCUSED:focused-big", "FOCUSED:focused-small",
            ),
            order,
        )
    }

    @Test
    fun pressureLevelsCoverTheirSteps() {
        assertEquals(setOf(EvictionStep.IDLE), MemoryPressure.UI_HIDDEN.steps)
        assertEquals(setOf(EvictionStep.IDLE, EvictionStep.NOT_VISIBLE), MemoryPressure.RUNNING_LOW.steps)
        assertEquals(setOf(EvictionStep.IDLE, EvictionStep.NOT_VISIBLE, EvictionStep.PRESSURE_HOOKS), MemoryPressure.BACKGROUND.steps)
        assertEquals(EvictionStep.entries.toSet(), MemoryPressure.COMPLETE.steps)
    }

    @Test
    fun whenClauseStateMappingAndPrecedence() {
        assertEquals(LspStateValue.OFF, LspStateValue.of(SessionState.NotInstalled))
        assertEquals(LspStateValue.STARTING, LspStateValue.of(SessionState.Backoff(1, 0)))
        assertEquals(LspStateValue.READY, LspStateValue.of(SessionState.Idle(0)))
        assertEquals(LspStateValue.OVER_BUDGET, LspStateValue.of(SessionState.Stopped(StopReason.EVICTED)))
        assertEquals(LspStateValue.CRASHED, LspStateValue.of(SessionState.Failed(dev.easyide.lsp.session.FailReason.CrashLoop, emptyList())))
        assertEquals(LspStateValue.OFF, LspStateValue.of(SessionState.Failed(dev.easyide.lsp.session.FailReason.Disabled, emptyList())))
        assertEquals(LspStateValue.READY, LspStateValue.best(listOf(LspStateValue.CRASHED, LspStateValue.READY, LspStateValue.OFF)))
        assertEquals(LspStateValue.OVER_BUDGET, LspStateValue.best(listOf(LspStateValue.CRASHED, LspStateValue.OVER_BUDGET)))
        assertEquals(LspStateValue.OFF, LspStateValue.best(emptyList()))
    }
}
