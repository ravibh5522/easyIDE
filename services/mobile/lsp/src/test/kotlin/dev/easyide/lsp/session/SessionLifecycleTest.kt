package dev.easyide.lsp.session

import dev.easyide.lsp.jsonrpc.RpcMessage
import dev.easyide.lsp.testing.FakeLanguageServer
import dev.easyide.lsp.testing.FakeServerLauncher
import dev.easyide.lsp.testing.LspTestHarness
import dev.easyide.lsp.testing.Reply
import dev.easyide.lsp.testing.serverConfig
import dev.easyide.lsp.testing.testSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleTest {
    private val h = LspTestHarness()
    private val key get() = h.key("e", "p", "pyright")
    private val uri = "file:///workspace/main.py"

    @After
    fun tearDown() = h.close()

    private suspend fun startPyright(config: ServerConfig = serverConfig("pyright"), text: String = "x = 1\n"): FakeLanguageServer {
        h.setServers("e", "p", config)
        h.manager.documentStore("e", "p").open(uri, "python", text)
        h.awaitState(key) { it == SessionState.Running }
        return h.launcher.latest(key)
    }

    @Test
    fun firstDocumentStartsTheServerWithInitializeInitializedAndDidOpen() = runBlocking {
        h.launcher.configure = { _, _ -> capabilities = INCREMENTAL }
        val server = startPyright()
        val init = server.requests("initialize").single().params!!.jsonObject
        assertEquals(JsonNull, init["processId"])
        assertEquals("file:///workspace", init["rootUri"]!!.jsonPrimitive.content)
        assertEquals("/workspace", init["rootPath"]!!.jsonPrimitive.content)
        val folders = init["workspaceFolders"]!!.jsonArray
        assertEquals(1, folders.size)
        assertEquals("file:///workspace", folders[0].jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals("utf-16", init["capabilities"]!!.jsonObject["general"]!!.jsonObject["positionEncodings"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("messages", init["trace"]!!.jsonPrimitive.content)
        val open = server.awaitNotifications("textDocument/didOpen", 1).single().params!!.jsonObject["textDocument"]!!.jsonObject
        assertEquals(uri, open["uri"]!!.jsonPrimitive.content)
        assertEquals(1, open["version"]!!.jsonPrimitive.int)
        val order = server.received.map { (it as? RpcMessage.Request)?.method ?: (it as? RpcMessage.Notification)?.method }
        assertTrue(order.indexOf("initialized") in 1 until order.indexOf("textDocument/didOpen"))
        assertEquals(listOf("pyright", "--stdio"), h.launcher.launches.single().argv)
    }

    @Test
    fun crashGoesToBackoffThenRunningWithDocumentsReopened() = runBlocking {
        h.launcher.configure = { _, _ -> capabilities = INCREMENTAL }
        val first = startPyright()
        first.stderrLine("Segmentation fault")
        first.crash()
        h.awaitState(key) { it is SessionState.Backoff }
        h.awaitState(key) { it == SessionState.Running }
        val second = h.launcher.latest(key)
        assertNotEquals(first, second)
        second.awaitNotifications("textDocument/didOpen", 1)
        assertTrue(h.manager.session(key)!!.log.snapshot().any { it.contains("stderr: Segmentation fault") })
    }

    @Test
    fun repeatedCrashesFailWithTheStderrTail() = runBlocking {
        h.launcher.configure = { _, _ ->
            capabilities = INCREMENTAL
            onRequest("initialize") {
                stderrLine("ModuleNotFoundError: No module named 'pyright'")
                crash()
                Reply.Never
            }
        }
        h.setServers("e", "p", serverConfig("pyright"))
        h.manager.documentStore("e", "p").open(uri, "python", "x")
        val failed = h.awaitState(key) { it is SessionState.Failed } as SessionState.Failed
        assertEquals(FailReason.CrashLoop, failed.reason)
        // maxRetries = 2: the first crash and two retries, then the third retry is refused.
        assertEquals(3, h.launcher.servers(key).size)
        assertTrue(failed.stderrTail.any { it.contains("ModuleNotFoundError") })
        h.launcher.configure = { _, _ -> capabilities = INCREMENTAL }
        h.manager.restart(key)
        h.awaitState(key) { it == SessionState.Running }
        Unit
    }

    @Test
    fun initializeTimeoutFailsAndKillsTheProcess() = runBlocking {
        h.launcher.configure = { _, _ -> onRequest("initialize") { Reply.Never } }
        h.setServers("e", "p", serverConfig("pyright", startupTimeoutSec = 1))
        h.manager.documentStore("e", "p").open(uri, "python", "x")
        val failed = h.awaitState(key) { it is SessionState.Failed } as SessionState.Failed
        assertEquals(FailReason.InitTimeout, failed.reason)
        assertTrue(h.launcher.latest(key).process.killed)
    }

    @Test
    fun aPositionEncodingWeDidNotOfferIsAProtocolFailure() = runBlocking {
        h.launcher.configure = { _, _ -> capabilities = buildJsonObject { put("positionEncoding", JsonPrimitive("utf-8")) } }
        h.setServers("e", "p", serverConfig("pyright"))
        h.manager.documentStore("e", "p").open(uri, "python", "x")
        val failed = h.awaitState(key) { it is SessionState.Failed } as SessionState.Failed
        assertTrue(failed.reason is FailReason.ProtocolError)
    }

    @Test
    fun spawnFailuresAreReportedPerCause() = runBlocking {
        h.launcher.launchFailure = FakeServerLauncher.NOT_READY
        h.setServers("e", "p", serverConfig("pyright"))
        h.manager.documentStore("e", "p").open(uri, "python", "x")
        assertEquals(FailReason.EnvironmentNotReady, (h.awaitState(key) { it is SessionState.Failed } as SessionState.Failed).reason)
        h.launcher.launchFailure = java.io.IOException("proot: cannot exec")
        h.manager.restart(key)
        val failed = h.awaitState(key) { it is SessionState.Failed && it.reason is FailReason.SpawnFailed } as SessionState.Failed
        assertEquals(FailReason.SpawnFailed("proot: cannot exec"), failed.reason)
    }

    @Test
    fun notInstalledStaysOffUntilAProbeSucceeds() = runBlocking {
        h.launcher.installed = { false }
        h.setServers("e", "p", serverConfig("pyright"))
        h.manager.documentStore("e", "p").open(uri, "python", "x")
        delay(SETTLE_MS)
        assertEquals(SessionState.NotInstalled, h.manager.statuses.value[key]!!.state)
        assertTrue(h.launcher.launches.isEmpty())
        h.launcher.installed = { true }
        h.manager.retryProbe(key)
        h.awaitState(key) { it == SessionState.Running }
        Unit
    }

    @Test
    fun lastDocumentClosedGoesIdleThenIdleTimeoutShutsDown() = runBlocking {
        val server = startPyright(serverConfig("pyright", idleShutdownSec = 1))
        h.manager.documentStore("e", "p").close(uri)
        h.awaitState(key) { it is SessionState.Idle }
        val stopped = h.awaitState(key) { it is SessionState.Stopped } as SessionState.Stopped
        assertEquals(StopReason.IDLE_TIMEOUT, stopped.reason)
        assertEquals(1, server.requests("shutdown").size)
        assertEquals(1, server.notifications("exit").size)
        assertFalse(server.process.killed)
    }

    @Test
    fun reopeningADocumentWhileIdleReturnsToRunning() = runBlocking {
        val server = startPyright()
        val store = h.manager.documentStore("e", "p")
        store.close(uri)
        h.awaitState(key) { it is SessionState.Idle }
        store.open(uri, "python", "y")
        h.awaitState(key) { it == SessionState.Running }
        server.awaitNotifications("textDocument/didOpen", 2)
        Unit
    }

    @Test
    fun releaseProjectClosesDocumentsAndKeepsTheServerWarm() = runBlocking {
        val server = startPyright()
        h.manager.releaseProject("e", "p")
        h.awaitState(key) { it is SessionState.Idle }
        server.awaitNotifications("textDocument/didClose", 1)
        assertTrue(server.process.isAlive)
    }

    @Test
    fun userStopAndStartAndRestart() = runBlocking {
        startPyright()
        h.manager.stop(key)
        assertEquals(StopReason.USER, (h.awaitState(key) { it is SessionState.Stopped } as SessionState.Stopped).reason)
        // A document need does not restart a user-stopped server.
        h.manager.ensureStarted("e", "p", "python")
        delay(SETTLE_MS)
        assertEquals(SessionState.Stopped(StopReason.USER), h.manager.statuses.value[key]!!.state)
        h.manager.start(key)
        h.awaitState(key) { it == SessionState.Running }
        val launches = h.launcher.launches.size
        h.manager.restart(key)
        withTimeout(WAIT_MS) { while (h.launcher.launches.size == launches) delay(POLL_MS) }
        h.awaitState(key) { it == SessionState.Running }
        Unit
    }

    @Test
    fun configChangeRestartsAndDisableStops() = runBlocking {
        val first = startPyright()
        h.setServers("e", "p", serverConfig("pyright", command = listOf("pyright-langserver", "--stdio")))
        withTimeout(WAIT_MS) { while (h.launcher.launches.size < 2) delay(POLL_MS) }
        h.awaitState(key) { it == SessionState.Running }
        assertEquals(1, first.requests("shutdown").size)
        assertEquals(listOf("pyright-langserver", "--stdio"), h.launcher.launches.last().argv)
        h.setServers("e", "p", serverConfig("pyright", command = listOf("pyright-langserver", "--stdio"), enabled = false))
        assertEquals(FailReason.Disabled, (h.awaitState(key) { it is SessionState.Failed } as SessionState.Failed).reason)
        h.setServers("e", "p", serverConfig("pyright", command = listOf("pyright-langserver", "--stdio")))
        h.awaitState(key) { it == SessionState.Running }
        // A server removed from the config (extension uninstalled) is stopped and forgotten.
        val last = h.launcher.latest(key)
        h.setServers("e", "p")
        withTimeout(WAIT_MS) { while (last.process.isAlive || h.manager.statuses.value.containsKey(key)) delay(POLL_MS) }
        delay(SETTLE_MS)
        assertFalse(h.manager.statuses.value.containsKey(key))
    }

    @Test
    fun garbageOnStdoutIsACrash() = runBlocking {
        val server = startPyright()
        server.emitGarbage()
        h.awaitState(key) { it is SessionState.Backoff }
        withTimeout(WAIT_MS) { while (!server.process.killed) delay(POLL_MS) }
    }

    @Test
    fun twoProjectsInOneEnvironmentGetTwoSessions() = runBlocking {
        h.setServers("e", "p", serverConfig("pyright"))
        h.setServers("e", "q", serverConfig("pyright"))
        h.manager.documentStore("e", "p").open(uri, "python", "p")
        h.manager.documentStore("e", "q").open(uri, "python", "q")
        h.awaitState(h.key("e", "p", "pyright")) { it == SessionState.Running }
        h.awaitState(h.key("e", "q", "pyright")) { it == SessionState.Running }
        assertEquals(2, h.launcher.launches.size)
        val qText = h.launcher.latest(h.key("e", "q", "pyright")).awaitNotifications("textDocument/didOpen", 1)
            .single().params!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("q", qText)
    }

    @Test
    fun rootMarkersGateEligibility() = runBlocking {
        h.setServers("e", "p", serverConfig("gopls", languages = setOf("go"), rootMarkers = listOf("go.mod")))
        val goKey = h.key("e", "p", "gopls")
        h.manager.documentStore("e", "p").open("file:///workspace/a.go", "go", "package a")
        delay(SETTLE_MS)
        assertTrue(h.launcher.launches.isEmpty())
        java.io.File(h.locator.projectDir("e", "p"), "go.mod").writeText("module a\n")
        h.manager.ensureStarted("e", "p", "go")
        h.awaitState(goKey) { it == SessionState.Running }
        Unit
    }

    @Test
    fun traceChangeSendsSetTrace() = runBlocking {
        val server = startPyright()
        h.settings.value = testSettings().copy(trace = dev.easyide.lsp.TraceLevel.VERBOSE)
        val n = server.awaitNotifications("\$/setTrace", 1).single()
        assertEquals("verbose", n.params!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val SETTLE_MS = 300L
        const val WAIT_MS = 8000L
        const val POLL_MS = 10L
        val INCREMENTAL: JsonObject = Json.parseToJsonElement("""{"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":false}}}""").jsonObject
    }
}
