package dev.easyide.app.extensions.host

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.action.ExecOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Runs a started process to completion for `sandboxExec` capture/silent: stdin closed,
 * stdout and stderr drained concurrently (a full pipe would otherwise stall the child),
 * each kept up to [limitBytes] and flagged when cut. The process is killed at
 * [timeoutMs] and whenever the calling coroutine is cancelled, which is also what
 * unblocks the readers (a blocked pipe read ignores interrupts).
 */
object ProcessCapture {

    suspend fun run(process: Process, limitBytes: Int, timeoutMs: Long, keep: Boolean, io: CoroutineDispatcher): ExecOutcome = try {
        coroutineScope {
            runCatchingIo { process.outputStream.close() }
            val out = async(io) { drain(process.inputStream, if (keep) limitBytes else 0) }
            val err = async(io) { drain(process.errorStream, limitBytes) }
            val exit = withTimeoutOrNull(timeoutMs) { runInterruptible(io) { process.waitFor() } }
            if (exit == null) {
                process.destroyForcibly()
                ExecOutcome.TimedOut(err.await().text.takeLast(ExtensionPolicy.STDERR_TAIL_CHARS))
            } else {
                val o = out.await()
                val e = err.await()
                ExecOutcome.Exited(exit, if (keep) o.text else "", if (keep) e.text else "", o.truncated || e.truncated)
            }
        }
    } finally {
        // Cancellation or a failed reader: never leave the child running.
        if (process.isAlive) process.destroyForcibly()
    }

    internal class Captured(val text: String, val truncated: Boolean)

    /** Reads to EOF, keeping the first [limit] bytes; the rest is read and dropped so the child never blocks. */
    internal fun drain(input: InputStream, limit: Int): Captured {
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        try {
            input.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    val room = (limit - kept.size()).coerceAtLeast(0)
                    if (room > 0) kept.write(buffer, 0, minOf(n, room))
                    total += n
                }
            }
        } catch (e: IOException) {
            // The process was destroyed mid-read (timeout or cancel): keep what arrived.
        }
        return Captured(decode(kept.toByteArray()), total > limit)
    }

    /** UTF-8 with replacement: a cut in the middle of a character must not fail the step. */
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private inline fun runCatchingIo(block: () -> Unit) {
        try { block() } catch (e: IOException) { /* stdin already closed by the child */ }
    }

    private const val BUFFER_BYTES = 16 * 1024
}
