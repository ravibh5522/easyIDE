package dev.easyide.lsp.testing

import dev.easyide.lsp.jsonrpc.FrameReader
import dev.easyide.lsp.jsonrpc.FrameWriter
import dev.easyide.lsp.jsonrpc.RpcCodec
import dev.easyide.lsp.jsonrpc.RpcId
import dev.easyide.lsp.jsonrpc.RpcMessage
import dev.easyide.lsp.jsonrpc.ResponseError
import dev.easyide.lsp.session.ServerProcessHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A process whose three streams are in-memory pipes; the fake server sits on the other ends. */
class FakeProcess : ServerProcessHandle {
    val toServer = TestPipe()
    val fromServer = TestPipe()
    val errors = TestPipe()
    val exit = CompletableDeferred<Int>()

    override val stdin get() = toServer.output
    override val stdout get() = fromServer.input
    override val stderr get() = errors.input
    override val isAlive: Boolean get() = !exit.isCompleted

    var terminated = false
        private set
    var killed = false
        private set

    override fun terminate() {
        terminated = true
        end(SIGTERM_EXIT)
    }

    override fun kill() {
        killed = true
        end(SIGKILL_EXIT)
    }

    override suspend fun awaitExit(): Int = exit.await()

    /** The process exits: its output ends and its input is gone. */
    fun end(code: Int) {
        fromServer.closeWrite()
        errors.closeWrite()
        toServer.closeRead()
        exit.complete(code)
    }

    companion object {
        const val SIGTERM_EXIT = 143
        const val SIGKILL_EXIT = 137
    }
}

/** What a scripted handler answers: a result, an error, or nothing at all (a hang). */
sealed interface Reply {
    data class Result(val value: JsonElement) : Reply
    data class Error(val code: Int, val message: String) : Reply
    data object Never : Reply
}

/**
 * An in-process language server speaking real `Content-Length` framed JSON-RPC over a
 * [FakeProcess]'s pipes (lsp-client.md sec 11 FakeServer). Handlers are scripted per method;
 * every client message is recorded in arrival order.
 */
class FakeLanguageServer(val process: FakeProcess, private val scope: CoroutineScope) {
    val received = CopyOnWriteArrayList<RpcMessage>()
    private val handlers = ConcurrentHashMap<String, suspend (JsonElement?) -> Reply>()
    private val clientReplies = ConcurrentHashMap<Long, CompletableDeferred<RpcMessage.Response>>()
    private val writer = FrameWriter(process.fromServer.output)
    private val ids = AtomicLong(FIRST_SERVER_ID)

    /** Advertised in the default `initialize` handler; incremental sync unless a test says otherwise. */
    var capabilities: JsonObject = buildJsonObject { put("textDocumentSync", JsonPrimitive(INCREMENTAL_SYNC)) }

    /** Exit code used when the client sends `exit`. */
    var exitCode = 0

    init {
        onRequest("initialize") { Reply.Result(buildJsonObject { put("capabilities", capabilities) }) }
        onRequest("shutdown") { Reply.Result(JsonNull) }
    }

    fun onRequest(method: String, handler: suspend (JsonElement?) -> Reply) {
        handlers[method] = handler
    }

    fun onResult(method: String, result: (JsonElement?) -> JsonElement) = onRequest(method) { Reply.Result(result(it)) }

    fun start() {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    fun notifyClient(method: String, params: JsonElement?) = send(RpcMessage.Notification(method, params))

    /** Sends a server->client request and waits for the client's answer. */
    suspend fun requestClient(method: String, params: JsonElement?): RpcMessage.Response {
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<RpcMessage.Response>()
        clientReplies[id] = reply
        send(RpcMessage.Request(RpcId.Num(id), method, params))
        return withTimeout(WAIT_MS) { reply.await() }
    }

    fun stderrLine(line: String) {
        process.errors.output.write("$line\n".toByteArray())
    }

    /** Writes bytes that are not a valid frame, like a stray `print` to stdout. */
    fun emitGarbage() {
        process.fromServer.output.write("hello from print()\r\n\r\n".toByteArray())
    }

    fun crash(code: Int = CRASH_EXIT) = process.end(code)

    fun notifications(method: String): List<RpcMessage.Notification> =
        received.filterIsInstance<RpcMessage.Notification>().filter { it.method == method }

    fun requests(method: String): List<RpcMessage.Request> =
        received.filterIsInstance<RpcMessage.Request>().filter { it.method == method }

    /** Waits until a received message matches. */
    suspend fun awaitMessage(predicate: (RpcMessage) -> Boolean): RpcMessage = withTimeout(WAIT_MS) {
        while (received.none(predicate)) delay(POLL_MS)
        received.first(predicate)
    }

    suspend fun awaitNotifications(method: String, count: Int): List<RpcMessage.Notification> = withTimeout(WAIT_MS) {
        while (notifications(method).size < count) delay(POLL_MS)
        notifications(method)
    }

    private fun send(message: RpcMessage) {
        synchronized(writer) {
            try {
                writer.write(RpcCodec.encode(message))
            } catch (e: IOException) {
                // The client end is gone; a real server would get EPIPE and die.
            }
        }
    }

    private suspend fun readLoop() {
        val reader = FrameReader(process.toServer.input)
        while (true) {
            val frame = try {
                reader.read() ?: break
            } catch (e: IOException) {
                break
            }
            val message = RpcCodec.decode(frame.json)
            received += message
            when (message) {
                is RpcMessage.Request -> scope.launch { answer(message) }
                is RpcMessage.Response -> (message.id as? RpcId.Num)?.let { clientReplies.remove(it.value)?.complete(message) }
                is RpcMessage.Notification -> if (message.method == "exit") process.end(exitCode)
            }
        }
    }

    private suspend fun answer(request: RpcMessage.Request) {
        val handler = handlers[request.method]
        val reply = handler?.invoke(request.params) ?: Reply.Error(METHOD_NOT_FOUND, "no handler for ${request.method}")
        when (reply) {
            is Reply.Result -> send(RpcMessage.Response(request.id, reply.value, null))
            is Reply.Error -> send(RpcMessage.Response(request.id, null, ResponseError(reply.code, reply.message)))
            Reply.Never -> Unit
        }
    }

    companion object {
        const val WAIT_MS = 5000L
        const val POLL_MS = 5L
        const val CRASH_EXIT = 1
        const val METHOD_NOT_FOUND = -32601
        const val FIRST_SERVER_ID = 1000L
        const val INCREMENTAL_SYNC = 2

        fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

        fun str(s: String) = JsonPrimitive(s)
    }
}
