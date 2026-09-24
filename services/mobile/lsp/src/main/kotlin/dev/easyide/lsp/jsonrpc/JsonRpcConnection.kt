package dev.easyide.lsp.jsonrpc

import dev.easyide.lsp.TraceLevel
import dev.easyide.lsp.json.long
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Answers the server's requests and notifications. */
interface RpcHandler {
    /** @throws RpcErrorException to reply with an error (e.g. `METHOD_NOT_FOUND`). */
    suspend fun onRequest(method: String, params: JsonElement?): JsonElement

    /** Called in arrival order, one at a time. */
    suspend fun onNotification(method: String, params: JsonElement?)
}

/** Sink for trace lines; [level] is read per message so `lsp.trace` changes apply live. */
interface RpcTracer {
    val level: TraceLevel
    fun record(line: String)
}

/** What a request ended with. A server `null` result is `Result(JsonNull)`. */
sealed interface RpcOutcome {
    data class Result(val value: JsonElement) : RpcOutcome
    data class Error(val error: ResponseError) : RpcOutcome
    data object Timeout : RpcOutcome
}

/**
 * JSON-RPC 2.0 over one pair of byte streams.
 *
 * Threads: one blocking reader and one writer on [ioDispatcher]; notifications are handed to
 * [handler] in wire order by one pump coroutine, and each server request gets its own job on
 * [handlerDispatcher] (it may suspend on a UI prompt) that `$/cancelRequest` can cancel. The
 * writer drains an unlimited channel and is the only code that touches [output], so the
 * enqueue order is the wire order.
 *
 * Correlation state is lock-free (`ConcurrentHashMap`) instead of dispatcher-confined, so
 * the reader can complete a call without hopping threads.
 */
class JsonRpcConnection(
    input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val handlerDispatcher: CoroutineDispatcher,
    private val handler: RpcHandler,
    private val tracer: RpcTracer,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class PendingCall(val method: String, val sentAtNanos: Long) {
        val response = CompletableDeferred<RpcMessage.Response>()
    }

    private val reader = FrameReader(input)
    private val writer = FrameWriter(output)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingCall>()
    private val incoming = ConcurrentHashMap<RpcId, Job>()
    private val outgoing = Channel<RpcMessage>(Channel.UNLIMITED)
    private val notifications = Channel<RpcMessage.Notification>(Channel.UNLIMITED)
    private val closeReason = CompletableDeferred<CloseReason>()
    private val jobs = mutableListOf<Job>()

    /** Completes once, with the first reason the connection ended. */
    val closed: Deferred<CloseReason> get() = closeReason

    val isOpen: Boolean get() = !closeReason.isCompleted

    /** Starts the reader, writer and notification pump. Call once. */
    fun start() {
        jobs += scope.launch(ioDispatcher) { readLoop() }
        // Not in [jobs]: on close the writer drains what is queued (a final `exit`) and then
        // closes stdin itself, instead of being cancelled mid-queue.
        scope.launch(ioDispatcher) { writeLoop() }
        jobs += scope.launch(handlerDispatcher) {
            for (n in notifications) {
                try {
                    handler.onNotification(n.method, n.params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failing port must not stop later notifications (diagnostics, progress).
                    tracer.record("notification ${n.method} handler failed: ${e.message}")
                }
            }
        }
    }

    /** Queues a notification; dropped silently once closed (the session is already crashing). */
    fun notify(method: String, params: JsonElement?) {
        if (isOpen) outgoing.trySend(RpcMessage.Notification(method, params))
    }

    /**
     * Sends a request and waits up to [timeoutMs]. If the caller is cancelled or the timeout
     * fires first, `$/cancelRequest` is sent and the late response is dropped as unknown-id.
     *
     * @throws ConnectionClosedException if the connection closes before the response.
     */
    suspend fun request(method: String, params: JsonElement?, timeoutMs: Long): RpcOutcome {
        val id = nextId.getAndIncrement()
        val call = PendingCall(method, nanoTime())
        pending[id] = call
        try {
            // Checked after registering: close() completes the reason before failing the
            // pending map, so either this sees the close or close() sees this call.
            if (closeReason.isCompleted) throw ConnectionClosedException(closeReason.await())
            outgoing.trySend(RpcMessage.Request(RpcId.Num(id), method, params))
            val response = withTimeoutOrNull(timeoutMs) { call.response.await() }
            if (response == null) {
                tracer.record("timeout $method ${timeoutMs}ms")
                return RpcOutcome.Timeout
            }
            return response.error?.let { RpcOutcome.Error(it) } ?: RpcOutcome.Result(response.result ?: JsonNull)
        } finally {
            if (pending.remove(id) != null && isOpen) {
                outgoing.trySend(RpcMessage.Notification(CANCEL_REQUEST, buildJsonObject { put(ID, JsonPrimitive(id)) }))
            }
        }
    }

    /**
     * Ends the connection: pending calls fail with [ConnectionClosedException], server-request
     * handlers are cancelled, the writer flushes what is already queued and closes stdin. The reader ends when its stream reaches EOF,
     * which the owner causes by stopping the process. Idempotent; the first reason wins.
     */
    fun close(reason: CloseReason) {
        if (!closeReason.complete(reason)) return
        val error = ConnectionClosedException(reason)
        pending.values.forEach { it.response.completeExceptionally(error) }
        incoming.values.forEach { it.cancel() }
        outgoing.close()
        notifications.close()
        jobs.forEach { it.cancel() }
    }

    private suspend fun readLoop() {
        close(runInterruptible { readUntilClosed() })
    }

    /** Blocking; returns why reading stopped. Any bad byte ends the connection (no resync). */
    private fun readUntilClosed(): CloseReason {
        while (true) {
            val frame = try {
                reader.read() ?: return CloseReason.TransportClosed
            } catch (e: ProtocolException) {
                return CloseReason.ProtocolError(e.message.orEmpty())
            } catch (e: IOException) {
                return CloseReason.TransportClosed
            }
            val message = try {
                RpcCodec.decode(frame.json)
            } catch (e: ProtocolException) {
                return CloseReason.ProtocolError(e.message.orEmpty())
            }
            dispatch(message, frame)
        }
    }

    private suspend fun writeLoop() {
        try {
            for (message in outgoing) {
                val json = RpcCodec.encode(message)
                val bytes = try {
                    runInterruptible { writer.write(json) }
                } catch (e: IOException) {
                    close(CloseReason.TransportClosed)
                    return
                }
                trace("-->", message, bytes, json, latencyMs = null)
            }
        } finally {
            closeOutput()
        }
    }

    private fun closeOutput() {
        try {
            output.close()
        } catch (e: IOException) {
            // Already broken: the process is gone, which is the state we wanted anyway.
        }
    }

    private fun dispatch(message: RpcMessage, frame: Frame) {
        when (message) {
            is RpcMessage.Response -> onResponse(message, frame)
            is RpcMessage.Notification -> {
                trace("<--", message, frame.bytes, frame.json, latencyMs = null)
                if (message.method == CANCEL_REQUEST) cancelIncoming(message.params) else notifications.trySend(message)
            }
            is RpcMessage.Request -> {
                trace("<--", message, frame.bytes, frame.json, latencyMs = null)
                onRequest(message)
            }
        }
    }

    private fun onResponse(message: RpcMessage.Response, frame: Frame) {
        val id = message.id
        if (id == null) {
            tracer.record("server error without id: ${message.error?.code} ${message.error?.message}")
            return
        }
        val call = (id as? RpcId.Num)?.let { pending.remove(it.value) }
        if (call == null) {
            if (tracer.level == TraceLevel.VERBOSE) tracer.record("dropped response for unknown id $id")
            return
        }
        val latencyMs = (nanoTime() - call.sentAtNanos) / NANOS_PER_MS
        trace("<--", message, frame.bytes, frame.json, latencyMs, call.method)
        call.response.complete(message)
    }

    private fun onRequest(request: RpcMessage.Request) {
        val job = scope.launch(handlerDispatcher, start = CoroutineStart.LAZY) {
            try {
                val reply = try {
                    RpcMessage.Response(request.id, handler.onRequest(request.method, request.params), null)
                } catch (e: CancellationException) {
                    outgoing.trySend(errorReply(request.id, ErrorCodes.REQUEST_CANCELLED, "cancelled"))
                    throw e
                } catch (e: RpcErrorException) {
                    RpcMessage.Response(request.id, null, e.error)
                } catch (e: Exception) {
                    // The server must always get an answer; a failing port becomes an error reply.
                    errorReply(request.id, ErrorCodes.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
                }
                outgoing.trySend(reply)
            } finally {
                incoming.remove(request.id)
            }
        }
        incoming[request.id] = job
        job.start()
    }

    private fun cancelIncoming(params: JsonElement?) {
        val raw = params.obj?.get(ID)
        val id = raw.str?.let { RpcId.Str(it) } ?: raw.long?.let { RpcId.Num(it) } ?: return
        incoming[id]?.cancel()
    }

    private fun errorReply(id: RpcId, code: Int, text: String) =
        RpcMessage.Response(id, null, ResponseError(code, text))

    private fun trace(dir: String, message: RpcMessage, bytes: Int, json: JsonElement, latencyMs: Long?, method: String? = null) {
        val level = tracer.level
        if (level == TraceLevel.OFF) return
        val summary = when (message) {
            is RpcMessage.Request -> "request ${message.id.label()} ${message.method}"
            is RpcMessage.Notification -> "notification ${message.method}"
            is RpcMessage.Response -> buildString {
                append("response ${message.id?.label()}")
                method?.let { append(' ').append(it) }
                message.error?.let { append(" error ").append(it.code) }
            }
        }
        val timing = latencyMs?.let { ", $it ms" }.orEmpty()
        val body = if (level == TraceLevel.VERBOSE) " $json" else ""
        tracer.record("$dir $summary ($bytes B$timing)$body")
    }

    private fun RpcId.label(): String = when (this) {
        is RpcId.Num -> "#$value"
        is RpcId.Str -> "#\"$value\""
    }

    private companion object {
        const val CANCEL_REQUEST = "\$/cancelRequest"
        const val ID = "id"
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Convenience for handlers: an error that maps to `METHOD_NOT_FOUND`. */
fun methodNotFound(method: String): RpcErrorException =
    RpcErrorException(ResponseError(ErrorCodes.METHOD_NOT_FOUND, "unhandled method $method"))

/** `RequestCancelled` / `ServerCancelled`: the request was abandoned, not failed. */
val ResponseError.isCancellation: Boolean
    get() = code == ErrorCodes.REQUEST_CANCELLED || code == ErrorCodes.SERVER_CANCELLED
