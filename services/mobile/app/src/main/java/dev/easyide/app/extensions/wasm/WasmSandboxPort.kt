package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.host.WorkspaceBridge
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.WasmPolicy
import dev.easyide.extwasm.host.ExecOutput
import dev.easyide.extwasm.host.ExecRequest
import dev.easyide.extwasm.host.HandleSink
import dev.easyide.extwasm.host.SandboxPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `sandbox.exec` / `sandbox.kill` in the open workspace's environment, through the same
 * process APIs as the L1 `sandboxExec` step (extension-runtime.md 8.5): `capture` starts a
 * piped process ([WorkspaceBridge.startCaptured]: argv, clean guest env plus the request's
 * env, which the host has already stripped of reserved keys) and streams its output as
 * `sandbox.output {stream, data}` events; `terminal` runs it in a command terminal tab.
 * Both end with one `sandbox.exit {code}` (or `{error}`), killed at [timeoutMs].
 *
 * Nothing started here is isolated per extension (decision 0002).
 */
class WasmSandboxPort(
    private val workspace: () -> WorkspaceBridge?,
    private val timeoutMs: () -> Long,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : SandboxPort {

    private val running = ConcurrentHashMap<Pair<String, Long>, Process>()

    override suspend fun start(extensionId: String, handle: Long, request: ExecRequest, sink: HandleSink) {
        val bridge = workspace() ?: throw HostCallException(ErrorCode.E_UNAVAILABLE, "no workspace is open")
        val cwd = request.cwd ?: WasmPolicy.PROJECT_ROOT
        if (request.output == ExecOutput.TERMINAL) {
            scope.launch {
                val title = request.argv.first()
                sink.finish(EXIT, outcome(bridge.runCommandTerminal(title, request.argv, cwd, request.env, timeoutMs())))
            }
            return
        }
        // Process-spawn boundary: a stopped environment or a failed exec is a typed answer to the guest.
        val process = try {
            bridge.startCaptured(request.argv, cwd, request.env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HostCallException(ErrorCode.E_UNAVAILABLE, "cannot start ${request.argv.first()}: ${e.message}")
        }
        val key = extensionId to handle
        running[key] = process
        scope.launch(io) {
            try {
                pump(process, request.stdin, sink)
            } finally {
                running.remove(key)
            }
        }
    }

    override suspend fun kill(extensionId: String, handle: Long) {
        running.remove(extensionId to handle)?.destroyForcibly()
            ?: throw HostCallException(ErrorCode.E_NOT_FOUND, "no running process for handle $handle")
    }

    private suspend fun pump(process: Process, stdin: String?, sink: HandleSink) {
        try {
            process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        } catch (e: IOException) {
            // The process closed stdin already; its output still counts.
        }
        val readers = listOf(STDOUT to process.inputStream, STDERR to process.errorStream).map { (name, stream) ->
            scope.launch(io) { stream(name, stream, sink) }
        }
        val code = withTimeoutOrNull(timeoutMs()) { runInterruptible(io) { process.waitFor() } }
        if (code == null) {
            process.destroyForcibly()
            process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        readers.forEach { it.join() }
        sink.finish(EXIT, if (code == null) error(ErrorCode.E_TIMEOUT, "killed after ${timeoutMs()} ms") else buildJsonObject { put(CODE, code) })
    }

    private fun stream(name: String, input: InputStream, sink: HandleSink) {
        // A decoding reader, so a chunk boundary never splits a multi-byte character.
        val buf = CharArray(CHUNK)
        try {
            input.reader(Charsets.UTF_8).use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) return
                    sink.emit(OUTPUT, buildJsonObject { put(STREAM, name); put(DATA, String(buf, 0, n)) })
                }
            }
        } catch (e: IOException) {
            // The process died or was killed: the exit event reports it.
        }
    }

    private fun outcome(o: ExecOutcome) = when (o) {
        is ExecOutcome.Exited -> buildJsonObject { put(CODE, o.exitCode) }
        is ExecOutcome.TimedOut -> error(ErrorCode.E_TIMEOUT, "killed after ${timeoutMs()} ms")
        is ExecOutcome.Unavailable -> error(ErrorCode.E_UNAVAILABLE, o.reason)
    }

    private fun error(code: ErrorCode, message: String) = buildJsonObject {
        put(ERROR, buildJsonObject { put(CODE_KEY, code.name); put(MESSAGE, message) })
    }

    private companion object {
        const val OUTPUT = "sandbox.output"
        const val EXIT = "sandbox.exit"
        const val STDOUT = "stdout"
        const val STDERR = "stderr"
        const val STREAM = "stream"
        const val DATA = "data"
        const val CODE = "code"
        const val CODE_KEY = "code"
        const val ERROR = "error"
        const val MESSAGE = "message"
        const val CHUNK = 8192
        const val KILL_WAIT_MS = 500L
    }
}
