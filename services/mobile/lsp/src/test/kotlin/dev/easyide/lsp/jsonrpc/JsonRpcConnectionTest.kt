package dev.easyide.lsp.jsonrpc

import dev.easyide.lsp.TraceLevel
import dev.easyide.lsp.testing.FakeLanguageServer
import dev.easyide.lsp.testing.FakeProcess
import dev.easyide.lsp.testing.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class JsonRpcConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val process = FakeProcess()
    private val server = FakeLanguageServer(process, scope)
    private val traces = CopyOnWriteArrayList<String>()
    private val notifications = CopyOnWriteArrayList<String>()
    private val handlerStarted = CompletableDeferred<Unit>()

    private val handler = object : RpcHandler {
        override suspend fun onRequest(method: String, params: JsonElement?): JsonElement = when (method) {
            "workspace/configuration" -> JsonPrimitive("configured")
            "window/showMessageRequest" -> {
                handlerStarted.complete(Unit)
                awaitCancellation()
            }
            "boom" -> throw IllegalStateException("port failed")
            else -> throw methodNotFound(method)
        }

        override suspend fun onNotification(method: String, params: JsonElement?) {
            notifications += method
        }
    }

    private val connection = JsonRpcConnection(
        input = process.stdout,
        output = process.stdin,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        handlerDispatcher = Dispatchers.Default.limitedParallelism(1),
        handler = handler,
        tracer = object : RpcTracer {
            override val level = TraceLevel.VERBOSE
            override fun record(line: String) {
                traces += line
            }
        },
    )

    init {
        server.start()
        connection.start()
    }

    @After
    fun tearDown() {
        process.end(0)
        scope.cancel()
    }

    @Test
    fun responsesAreCorrelatedByIdEvenOutOfOrder() = runBlocking {
        val release = CompletableDeferred<Unit>()
        server.onRequest("slow") { release.await(); Reply.Result(JsonPrimitive("slow")) }
        server.onResult("fast") { JsonPrimitive("fast") }
        val slow = async { connection.request("slow", null, TIMEOUT) }
        server.awaitMessage { it is RpcMessage.Request && it.method == "slow" }
        val fast = connection.request("fast", null, TIMEOUT)
        release.complete(Unit)
        assertEquals(RpcOutcome.Result(JsonPrimitive("fast")), fast)
        assertEquals(RpcOutcome.Result(JsonPrimitive("slow")), slow.await())
    }

    @Test
    fun nullResultIsDistinctFromTimeout() = runBlocking {
        server.onResult("nothing") { JsonNull }
        assertEquals(RpcOutcome.Result(JsonNull), connection.request("nothing", null, TIMEOUT))
    }

    @Test
    fun errorAnswersAreReturnedAsErrors() = runBlocking {
        server.onRequest("bad") { Reply.Error(ErrorCodes.CONTENT_MODIFIED, "modified") }
        val outcome = connection.request("bad", null, TIMEOUT) as RpcOutcome.Error
        assertEquals(ErrorCodes.CONTENT_MODIFIED, outcome.error.code)
    }

    @Test
    fun timeoutSendsCancelRequestAndTheLateResponseIsDropped() = runBlocking {
        val release = CompletableDeferred<Unit>()
        server.onRequest("hang") { release.await(); Reply.Result(JsonPrimitive("late")) }
        assertEquals(RpcOutcome.Timeout, connection.request("hang", null, SHORT))
        val id = server.requests("hang").single().id as RpcId.Num
        val cancel = server.awaitMessage { it is RpcMessage.Notification && it.method == "\$/cancelRequest" } as RpcMessage.Notification
        assertEquals(id.value, cancel.params!!.jsonObject["id"]!!.jsonPrimitive.long)
        release.complete(Unit)
        // The late answer must not break the connection or the next call.
        server.onResult("next") { JsonPrimitive(1) }
        assertEquals(RpcOutcome.Result(JsonPrimitive(1)), connection.request("next", null, TIMEOUT))
        assertTrue(traces.any { it.startsWith("timeout hang") })
    }

    @Test
    fun callerCancellationSendsCancelRequest() = runBlocking {
        server.onRequest("hang") { Reply.Never }
        val job = launch { connection.request("hang", null, TIMEOUT) }
        server.awaitMessage { it is RpcMessage.Request && it.method == "hang" }
        job.cancelAndJoin()
        server.awaitMessage { it is RpcMessage.Notification && it.method == "\$/cancelRequest" }
        Unit
    }

    @Test
    fun serverRequestsAreAnsweredWithTheirOwnIdShape() = runBlocking {
        val reply = server.requestClient("workspace/configuration", buildJsonObject {})
        assertEquals(JsonPrimitive("configured"), reply.result)
        val unknown = server.requestClient("window/unknown", null)
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, unknown.error!!.code)
        val failing = server.requestClient("boom", null)
        assertEquals(ErrorCodes.INTERNAL_ERROR, failing.error!!.code)
    }

    @Test
    fun serverCancelRequestCancelsTheHandlerAndRepliesCancelled() = runBlocking {
        val pending = async { server.requestClient("window/showMessageRequest", buildJsonObject {}) }
        withTimeout(TIMEOUT) { handlerStarted.await() }
        val id = FakeLanguageServer.FIRST_SERVER_ID
        server.notifyClient("\$/cancelRequest", buildJsonObject { put("id", JsonPrimitive(id)) })
        assertEquals(ErrorCodes.REQUEST_CANCELLED, pending.await().error!!.code)
    }

    @Test
    fun notificationsArriveInWireOrder() = runBlocking {
        repeat(COUNT) { server.notifyClient("n$it", null) }
        withTimeout(TIMEOUT) { while (notifications.size < COUNT) delay(POLL) }
        assertEquals((0 until COUNT).map { "n$it" }, notifications.toList())
    }

    @Test
    fun garbageOnStdoutClosesWithProtocolErrorAndFailsPendingCalls() = runBlocking {
        server.onRequest("hang") { Reply.Never }
        val pending = async { runCatching { connection.request("hang", null, TIMEOUT) } }
        server.awaitMessage { it is RpcMessage.Request && it.method == "hang" }
        server.emitGarbage()
        val reason = withTimeout(TIMEOUT) { connection.closed.await() }
        assertTrue(reason is CloseReason.ProtocolError)
        assertTrue(pending.await().exceptionOrNull() is ConnectionClosedException)
        assertFalse(connection.isOpen)
    }

    @Test
    fun processExitClosesAsTransportClosed() = runBlocking {
        server.crash()
        assertEquals(CloseReason.TransportClosed, withTimeout(TIMEOUT) { connection.closed.await() })
        val after = runCatching { connection.request("x", null, TIMEOUT) }
        assertTrue(after.exceptionOrNull() is ConnectionClosedException)
    }

    @Test
    fun verboseTraceRecordsDirectionMethodAndLatency() = runBlocking {
        server.onResult("traced") { JsonPrimitive(true) }
        connection.request("traced", buildJsonObject { put("k", JsonPrimitive("v")) }, TIMEOUT)
        assertTrue(traces.any { it.startsWith("--> request #") && it.contains("traced") && it.contains("\"k\":\"v\"") })
        assertTrue(traces.any { it.startsWith("<-- response #") && it.contains("traced") && it.contains(" ms)") })
    }

    private companion object {
        const val TIMEOUT = 5000L
        const val SHORT = 100L
        const val POLL = 5L
        const val COUNT = 50
    }
}
