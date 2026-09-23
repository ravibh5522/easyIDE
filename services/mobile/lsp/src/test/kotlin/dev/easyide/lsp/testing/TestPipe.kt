package dev.easyide.lsp.testing

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * An in-memory byte pipe. Unlike `PipedInputStream` it does not tie itself to the writing
 * thread (coroutines hop threads, and a dead writer thread makes `PipedInputStream` throw),
 * and each `write` arrives as its own chunk, so tests control how bytes are split.
 */
class TestPipe {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    @Volatile private var readerClosed = false
    @Volatile private var writerClosed = false

    val input: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val c = current
                if (c != null && pos < c.size) {
                    val n = minOf(len, c.size - pos)
                    c.copyInto(b, off, pos, pos + n)
                    pos += n
                    return n
                }
                if (c === EOF) return -1
                current = try {
                    chunks.take()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted")
                }
                pos = 0
                if (current === EOF) {
                    // Keep the sentinel so every later read sees EOF too.
                    chunks.put(EOF)
                    return -1
                }
            }
        }

        override fun close() {
            readerClosed = true
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (readerClosed || writerClosed) throw IOException("broken pipe")
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }

        override fun close() = closeWrite()
    }

    /** End of stream for the reader, after what is already queued. */
    fun closeWrite() {
        if (writerClosed) return
        writerClosed = true
        chunks.put(EOF)
    }

    /** The reading side went away: further writes fail like a broken pipe. */
    fun closeRead() {
        readerClosed = true
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
