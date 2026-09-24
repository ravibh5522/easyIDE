package dev.easyide.sandbox.shell

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
     * The read blocks instead of polling `available()`: polling either burns
     * wakeups while a command is idle or adds latency to every chunk.
     * Cancellation cannot rely on the thread interrupt [runInterruptible]
     * sends - a read blocked on a pipe ignores it - so a sibling coroutine
     * kills the process and closes the stream, which is what unblocks it.
     *
     * @param onLines called with each batch of complete lines.
     * @return the exit code; a cancelled caller gets CancellationException.
     */
    suspend fun stream(onLines: (List<String>) -> Unit): Int = coroutineScope {
        val finished = AtomicBoolean(false)
        val unblocker = launch(ioDispatcher) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) {
                    kill()
                    runCatching { process.inputStream.close() }
                }
            }
        }
        try {
            runInterruptible(ioDispatcher) { readUntilExit(onLines) }
        } finally {
            finished.set(true)
            unblocker.cancel()
        }
    }

    private fun readUntilExit(onLines: (List<String>) -> Unit): Int {
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
                // Returns -1 once every writer of the pipe has exited, so
                // output written just before exit is drained, not dropped.
                val read = stream.read(buffer)
                if (read < 0) break
                partial.append(String(buffer, 0, read))
                extractLines(partial, pending)
                flush(force = false)
            }
        } catch (cause: IOException) {
            // Boundary: the stream dies when the process is killed mid-read,
            // which is an ordinary outcome here, not a failure to report.
        }

        // A trailing line without a newline (a prompt like "Password: ")
        // still needs to reach the screen.
        if (partial.isNotEmpty()) pending += partial.toString()
        flush(force = true)

        return runCatching { process.waitFor() }.getOrDefault(CANCELLED_EXIT_CODE)
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

        /** Fast enough to feel live, slow enough not to flood recomposition. */
        const val FLUSH_INTERVAL_MS = 60L
        const val CANCELLED_EXIT_CODE = 130
    }
}
