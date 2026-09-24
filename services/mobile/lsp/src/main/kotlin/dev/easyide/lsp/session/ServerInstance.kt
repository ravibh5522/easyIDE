package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.TraceLevel
import dev.easyide.lsp.jsonrpc.CloseReason
import dev.easyide.lsp.jsonrpc.ConnectionClosedException
import dev.easyide.lsp.jsonrpc.JsonRpcConnection
import dev.easyide.lsp.jsonrpc.RpcHandler
import dev.easyide.lsp.jsonrpc.RpcOutcome
import dev.easyide.lsp.jsonrpc.RpcTracer
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.protocol.ClientCapabilitiesBuilder
import dev.easyide.lsp.protocol.ServerCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Result of the `initialize` round trip. */
internal sealed interface InitOutcome {
    data class Ok(val capabilities: ServerCapabilities) : InitOutcome
    data class Rejected(val detail: String) : InitOutcome
    data object TimedOut : InitOutcome

    /** The connection closed first; the exit watcher reports the crash. */
    data object Closed : InitOutcome
}

/**
 * One server process lifetime: its connection, stderr drain and exit watch. A restart makes a
 * new instance; the [LspSession] above it spans restarts. Everything here dies with [scope].
 *
 * @param onEnded called once, with the first cause, when the process or transport ends on its own.
 */
internal class ServerInstance(
    val generation: Int,
    val process: ServerProcessHandle,
    parent: CoroutineScope,
    serial: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    handler: RpcHandler,
    tracer: RpcTracer,
    private val onStderr: (String) -> Unit,
    private val onEnded: (SessionEvent) -> Unit,
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job) + serial)
    val connection = JsonRpcConnection(process.stdout, process.stdin, scope, io, serial, handler, tracer)
    var capabilities: ServerCapabilities? = null
    var sync: DocumentSync? = null

    /** The `lsp.trace` level this process was last told (in `initialize`, then `$/setTrace`). */
    var trace: TraceLevel = TraceLevel.OFF

    fun start() {
        connection.start()
        val stderrJob = scope.launch(io) { drainStderr() }
        val exited = scope.async(io) { process.awaitExit() }
        scope.launch {
            val ended = select<SessionEvent?> {
                exited.onAwait { code -> SessionEvent.Crashed("process exited with code $code") }
                connection.closed.onAwait { reason ->
                    when (reason) {
                        is CloseReason.ProtocolError -> SessionEvent.ProtocolError(reason.detail)
                        CloseReason.TransportClosed -> SessionEvent.Crashed("transport closed")
                        CloseReason.Requested -> null
                    }
                }
            } ?: return@launch
            // A dying server prints why just before it exits; let that reach the stderr tail
            // the Failed state shows, instead of racing the drain.
            if (ended is SessionEvent.Crashed) withTimeoutOrNull(LspPolicy.STDERR_DRAIN_GRACE_MS) { stderrJob.join() }
            onEnded(ended)
        }
    }

    /**
     * Sends `initialize` and validates the result (lsp-client.md sec 6): a `capabilities`
     * object is required, and the position encoding must be the one we offered.
     */
    suspend fun initialize(params: JsonObject, timeoutMs: Long): InitOutcome {
        val outcome = try {
            connection.request(METHOD_INITIALIZE, params, timeoutMs)
        } catch (e: ConnectionClosedException) {
            return InitOutcome.Closed
        }
        return when (outcome) {
            RpcOutcome.Timeout -> InitOutcome.TimedOut
            is RpcOutcome.Error -> InitOutcome.Rejected("initialize failed: ${outcome.error.code} ${outcome.error.message}")
            is RpcOutcome.Result -> validate(outcome.value)
        }
    }

    /** `shutdown` -> `exit` -> wait -> SIGTERM -> wait -> SIGKILL, each step bounded by the grace. */
    suspend fun shutdown(graceMs: Long) {
        if (connection.isOpen) {
            try {
                connection.request(METHOD_SHUTDOWN, null, graceMs)
            } catch (e: ConnectionClosedException) {
                // Already gone: nothing left to ask nicely.
            }
            connection.notify(METHOD_EXIT, null)
        }
        if (awaitExit(graceMs)) return
        process.terminate()
        if (awaitExit(graceMs)) return
        process.kill()
    }

    /** Tears everything down immediately; safe to call more than once. */
    fun dispose() {
        connection.close(CloseReason.Requested)
        if (process.isAlive) process.kill()
        scope.cancel()
    }

    private suspend fun awaitExit(timeoutMs: Long): Boolean =
        !process.isAlive || withTimeoutOrNull(timeoutMs) { process.awaitExit() } != null

    private fun validate(result: JsonElement): InitOutcome {
        val caps = result.obj?.get(KEY_CAPABILITIES).obj ?: return InitOutcome.Rejected("initialize result has no capabilities")
        val parsed = ServerCapabilities.fromJson(caps)
        val encoding = parsed.positionEncoding
        if (encoding != null && encoding != ClientCapabilitiesBuilder.OFFERED_POSITION_ENCODING) {
            return InitOutcome.Rejected("server chose position encoding $encoding, only utf-16 was offered")
        }
        return InitOutcome.Ok(parsed)
    }

    /**
     * Reads stderr bytes, splits on `\n`, decodes UTF-8 with replacement and truncates long
     * lines. Never parsed. Must run for the whole process life: an undrained pipe fills its
     * kernel buffer and blocks the server.
     */
    private suspend fun drainStderr() {
        runInterruptible {
            val input = process.stderr
            val line = ByteArrayOutputStream()
            val buf = ByteArray(STDERR_CHUNK)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val b = buf[i]
                        if (b == NEWLINE) {
                            emitLine(line)
                        } else if (line.size() < MAX_LINE_BYTES) {
                            line.write(b.toInt())
                        }
                    }
                }
            } catch (e: IOException) {
                // Pipe closed under us (process killed): the tail so far is all there is.
            }
            if (line.size() > 0) emitLine(line)
        }
    }

    private fun emitLine(line: ByteArrayOutputStream) {
        onStderr(String(line.toByteArray(), Charsets.UTF_8).trimEnd('\r'))
        line.reset()
    }

    private companion object {
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_SHUTDOWN = "shutdown"
        const val METHOD_EXIT = "exit"
        const val KEY_CAPABILITIES = "capabilities"
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val STDERR_CHUNK = 4096

        /** UTF-8 upper bound of a line the log keeps; the rest is dropped before decoding. */
        const val MAX_LINE_BYTES = LspPolicy.MAX_LOG_LINE_CHARS * LspPolicy.UTF8_BYTES_PER_UTF16_UNIT
    }
}
