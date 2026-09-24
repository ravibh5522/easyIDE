package dev.easyide.lsp.manager

import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import dev.easyide.lsp.testing.LspTestHarness
import dev.easyide.lsp.testing.serverConfig
import dev.easyide.lsp.testing.testSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ManagerBudgetTest {
    private var h = LspTestHarness(testSettings(maxServers = 2, globalBudgetMb = 1000))

    @After
    fun tearDown() = h.close()

    private fun open(lang: String) = h.manager.documentStore("e", "p").open("file:///workspace/a.$lang", lang, "x")

    private fun close(lang: String) = h.manager.documentStore("e", "p").close("file:///workspace/a.$lang")

    private fun k(id: String) = h.key("e", "p", id)

    private fun servers(vararg ids: String, budget: Int = 300) {
        h.setServers("e", "p", *ids.map { serverConfig(it, languages = setOf(it), memoryBudgetMb = budget) }.toTypedArray())
    }

    @Test
    fun maxServersEvictsTheIdleServerFirst() = runBlocking {
        servers("py", "go", "rs")
        open("py")
        h.awaitState(k("py")) { it == SessionState.Running }
        open("go")
        h.awaitState(k("go")) { it == SessionState.Running }
        close("py")
        h.awaitState(k("py")) { it is SessionState.Idle }
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.go"), "file:///workspace/a.go")
        open("rs")
        h.awaitState(k("rs")) { it == SessionState.Running }
        assertEquals(StopReason.EVICTED, (h.awaitState(k("py")) { it is SessionState.Stopped } as SessionState.Stopped).reason)
        assertEquals(SessionState.Running, h.manager.statuses.value[k("go")]!!.state)
    }

    @Test
    fun hiddenRunningServerIsEvictedBeforeTheFocusedOne() = runBlocking {
        servers("py", "go", "rs")
        open("py")
        open("go")
        h.awaitState(k("py")) { it == SessionState.Running }
        h.awaitState(k("go")) { it == SessionState.Running }
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.go"), "file:///workspace/a.go")
        delay(SETTLE_MS)
        open("rs")
        h.awaitState(k("rs")) { it == SessionState.Running }
        assertEquals(StopReason.EVICTED, (h.awaitState(k("py")) { it is SessionState.Stopped } as SessionState.Stopped).reason)
        // An evicted server restarts on its next need: focusing its document.
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.py"), "file:///workspace/a.py")
        h.awaitState(k("py")) { it == SessionState.Running }
        Unit
    }

    @Test
    fun admissionRefusesWhenOnlyFocusedServersCouldMakeRoom() = runBlocking {
        h.close()
        h = LspTestHarness(testSettings(maxServers = 1))
        servers("py", "go")
        open("py")
        h.awaitState(k("py")) { it == SessionState.Running }
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.py", "file:///workspace/a.go"), "file:///workspace/a.py")
        delay(SETTLE_MS)
        open("go")
        withTimeout(WAIT_MS) { while (h.manager.statuses.value[k("go")]?.pausedForMemory != true) delay(POLL_MS) }
        assertEquals(SessionState.Stopped(StopReason.NEVER_STARTED), h.manager.statuses.value[k("go")]!!.state)
        assertEquals(SessionState.Running, h.manager.statuses.value[k("py")]!!.state)
    }

    @Test
    fun projectedBudgetsCountTowardAdmission() = runBlocking {
        servers("py", "go", budget = 600)
        open("py")
        h.awaitState(k("py")) { it == SessionState.Running }
        close("py")
        h.awaitState(k("py")) { it is SessionState.Idle }
        // 600 + 600 > 1000: the idle server makes room even though maxServers (2) is not reached.
        open("go")
        h.awaitState(k("go")) { it == SessionState.Running }
        assertEquals(StopReason.EVICTED, (h.awaitState(k("py")) { it is SessionState.Stopped } as SessionState.Stopped).reason)
    }

    @Test
    fun overOwnBudgetTwiceRestartsOnceThenFails() = runBlocking {
        servers("py")
        open("py")
        h.awaitState(k("py")) { it == SessionState.Running }
        h.rss[k("py")] = OVER_300_MB_KB
        h.manager.sampleMemory()
        assertEquals(SessionState.Running, h.manager.statuses.value[k("py")]!!.state)
        h.manager.sampleMemory()
        withTimeout(WAIT_MS) { while (h.launcher.servers(k("py")).size < 2) delay(POLL_MS) }
        h.awaitState(k("py")) { it == SessionState.Running }
        h.manager.sampleMemory()
        h.manager.sampleMemory()
        val failed = h.awaitState(k("py")) { it is SessionState.Failed } as SessionState.Failed
        assertEquals(FailReason.OverBudget, failed.reason)
        assertEquals(LspStateValue.OVER_BUDGET, LspStateValue.of(failed))
    }

    @Test
    fun globalBreachEvictsInKillOrderUntilUnderTheTarget() = runBlocking {
        h.close()
        h = LspTestHarness(testSettings(maxServers = 3, globalBudgetMb = 1000))
        servers("py", "go", "rs", budget = 300)
        open("py")
        open("go")
        open("rs")
        for (id in listOf("py", "go", "rs")) h.awaitState(k(id)) { it == SessionState.Running }
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.rs"), "file:///workspace/a.rs")
        close("go")
        h.awaitState(k("go")) { it is SessionState.Idle }
        delay(SETTLE_MS)
        // 400 + 400 + 400 MB > 1000; evicting the idle one leaves 800 <= 850 (0.85 target).
        for (id in listOf("py", "go", "rs")) h.rss[k(id)] = MB_400_KB
        h.manager.sampleMemory()
        assertEquals(StopReason.EVICTED, (h.awaitState(k("go")) { it is SessionState.Stopped } as SessionState.Stopped).reason)
        delay(SETTLE_MS)
        assertEquals(SessionState.Running, h.manager.statuses.value[k("py")]!!.state)
        assertEquals(SessionState.Running, h.manager.statuses.value[k("rs")]!!.state)
        assertEquals(MB_400_KB, h.manager.statuses.value[k("rs")]!!.rssKb)
    }

    @Test
    fun memoryPressureLevelsFollowTheKillOrder() = runBlocking {
        h.close()
        h = LspTestHarness(testSettings(maxServers = 3))
        val hookCalls = AtomicInteger()
        h.manager.registerMemoryPressureHook { hookCalls.incrementAndGet() }
        servers("py", "go", "rs")
        open("py")
        open("go")
        open("rs")
        for (id in listOf("py", "go", "rs")) h.awaitState(k(id)) { it == SessionState.Running }
        h.manager.onVisibleUrisChanged("e", "p", setOf("file:///workspace/a.rs"), "file:///workspace/a.rs")
        close("go")
        h.awaitState(k("go")) { it is SessionState.Idle }
        h.manager.onMemoryPressure(MemoryPressure.UI_HIDDEN)
        h.awaitState(k("go")) { it is SessionState.Stopped }
        delay(SETTLE_MS)
        assertEquals(SessionState.Running, h.manager.statuses.value[k("py")]!!.state)
        h.manager.onMemoryPressure(MemoryPressure.BACKGROUND)
        h.awaitState(k("py")) { it is SessionState.Stopped }
        withTimeout(WAIT_MS) { while (hookCalls.get() == 0) delay(POLL_MS) }
        assertEquals(SessionState.Running, h.manager.statuses.value[k("rs")]!!.state)
        h.manager.onMemoryPressure(MemoryPressure.COMPLETE)
        h.awaitState(k("rs")) { it is SessionState.Stopped }
        assertTrue(hookCalls.get() >= 2)
    }

    private companion object {
        const val WAIT_MS = 8000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L
        const val OVER_300_MB_KB = 310L * 1024
        const val MB_400_KB = 400L * 1024
    }
}
