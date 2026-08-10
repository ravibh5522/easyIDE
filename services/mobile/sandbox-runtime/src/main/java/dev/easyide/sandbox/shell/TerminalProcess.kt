package dev.tabcode.sandbox.shell

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A running command you can watch and talk to.
 *
 * Replaces the previous run-to-completion model, which buffered everything and
 * only showed output once the process exited - so a long build looked frozen
 * and nothing could answer a prompt.
 *
 * Output is delivered in small time-based batches rather than per line: one UI
 * update per line is what caused an ANR on chatty commands, and one update per
 * second feels laggy. [FLUSH_INTERVAL_MS] is the compromise.
 *
 * Nothing is accumulated here - lines are emitted and forgotten - so a command
 * that prints forever cannot exhaust memory. Bounding the scrollback is the
 * UI's job.
 */
class TerminalProcess internal constructor(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val input = process.outputStream.bufferedWriter()

    val isAlive: Boolean get() = process.isAlive

    /** Sends a line to the process's stdin, as typing Enter would. */
    fun send(line: String) {
        runCatching {
            input.write(line)
            input.newLine()
            input.flush()
        }
    }

    /** Closes stdin, which is how a reader like `cat` sees end of input. */
    fun closeInput() {
        runCatching { input.close() }
    }

    fun kill() {
        runCatching { process.destroyForcibly() }
    }

    /**
     * Streams until the process exits or the coroutine is cancelled.
     *
     * @param onLines called with each batch of complete lines.
     * @return the exit code, or [CANCELLED_EXIT_CODE] if cancelled.
     */
    suspend fun stream(onLines: (List<String>) -> Unit): Int = withContext(ioDispatcher) {
        val stream = process.inputStream
        val buffer = ByteArray(READ_BUFFER)
        val partial = StringBuilder()
        val pending = mutableListOf<String>()
        var lastFlush = System.currentTimeMillis()

        fun flush(force: Boolean) {
            val now = System.currentTimeMillis()
            if (pending.isEmpty()) return
            if (!force && now - lastFlush < FLUSH_INTERVAL_MS) return
            onLines(pending.toList())
            pending.clear()
            lastFlush = now
        }

        try {
            while (true) {
                if (!currentCoroutineContext().isActive) {
                    kill()
                    flush(force = true)
                    return@withContext CANCELLED_EXIT_CODE
                }

                val available = stream.available()
                if (available > 0) {
                    val read = stream.read(buffer, 0, minOf(available, buffer.size))
                    if (read < 0) break
                    partial.append(String(buffer, 0, read))
                    extractLines(partial, pending)
                    flush(force = false)
                    continue
                }

                if (!process.isAlive) {
                    // Drain anything written between the last poll and exit.
                    val remaining = stream.readBytes()
                    if (remaining.isNotEmpty()) {
                        partial.append(String(remaining))
                        extractLines(partial, pending)
                    }
                    break
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (cause: IOException) {
            // Boundary: the stream dies when the process is killed mid-read,
            // which is an ordinary outcome here, not a failure to report.
        }

        // A trailing line without a newline (a prompt like "Password: ")
        // still needs to reach the screen.
        if (partial.isNotEmpty()) pending += partial.toString()
        flush(force = true)

        runCatching { process.waitFor() }.getOrDefault(CANCELLED_EXIT_CODE)
    }

    private fun extractLines(partial: StringBuilder, into: MutableList<String>) {
        while (true) {
            val newline = partial.indexOf("\n")
            if (newline < 0) break
            into += partial.substring(0, newline).lastOverwrite()
            partial.delete(0, newline + 1)
        }
    }

    /**
     * A carriage return means "back to column 0", so everything before the last
     * one on a line was overwritten on a real terminal. Without this, dpkg's
     * progress arrives as one multi-kilobyte line of dead text
     * ("Reading database ... 5%\rReading database ... 10%\r...") that then has
     * to be laid out in full.
     *
     * The trailing `\r` of a CRLF ending is dropped first, so a normal
     * CRLF-terminated line is not mistaken for an overwrite and emptied.
     */
    private fun String.lastOverwrite(): String = trimEnd('\r').substringAfterLast('\r')

    private companion object {
        const val READ_BUFFER = 16 * 1024
        const val POLL_INTERVAL_MS = 25L

        /** Fast enough to feel live, slow enough not to flood recomposition. */
        const val FLUSH_INTERVAL_MS = 60L
        const val CANCELLED_EXIT_CODE = 130
    }
}
