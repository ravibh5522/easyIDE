package dev.easyide.app.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A size-bounded log kept in [dir] as two segment files: [CURRENT] and [PREVIOUS]. When the
 * next line would push [CURRENT] past [maxSegmentBytes] it becomes [PREVIOUS] (the old
 * previous segment is dropped) and a fresh current starts, so the log never exceeds two
 * segments (512 KiB at the default) and always holds the newest lines.
 *
 * Writing: one persistent [FileOutputStream] in append mode and one `write(2)` per line.
 * Nothing is buffered in the process, so a line survives a crash of the app the moment
 * [append] returns (it does not survive power loss; an fsync per line is not worth its cost
 * for a diagnostic log). Open-append-close per line would cost three syscalls a line for no
 * added durability. One lock guards writes, rotation, reads and [clear]; the log is written
 * from many threads but at human rates, so contention is not a concern.
 *
 * Crash tolerance: a process killed mid-write leaves a last line without its newline. The
 * first append after reopening the file terminates that line first, so the torn text stays
 * one (undecodable, skipped) line instead of gluing itself to the next entry.
 *
 * I/O errors propagate as [IOException]; the caller decides whether that is fatal ([AppLog]
 * swallows it because a log must never crash the app).
 */
class RollingLogFile(
    private val dir: File,
    private val maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
) {
    private val lock = Any()
    private val current = File(dir, CURRENT)
    private val previous = File(dir, PREVIOUS)

    private var writer: FileOutputStream? = null
    private var currentBytes = 0L

    fun append(line: LogLine) {
        val bytes = (LogFormat.encode(line) + NEWLINE).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            var out = openWriter()
            if (currentBytes > 0 && currentBytes + bytes.size > maxSegmentBytes) out = rotate()
            out.write(bytes)
            currentBytes += bytes.size
        }
    }

    /** Every decodable line, oldest first. Undecodable lines (a torn write) are skipped. */
    fun readAll(): List<LogLine> = synchronized(lock) {
        (segmentLines(previous) + segmentLines(current)).mapNotNull(LogFormat::decode)
    }

    /** The newest [n] lines, oldest first. */
    fun tail(n: Int): List<LogLine> = if (n <= 0) emptyList() else readAll().takeLast(n)

    fun sizeBytes(): Long = synchronized(lock) { previous.length() + current.length() }

    /** Deletes both segments. Writing may continue afterwards. */
    fun clear() {
        synchronized(lock) {
            closeWriter()
            Files.deleteIfExists(previous.toPath())
            Files.deleteIfExists(current.toPath())
            currentBytes = 0
        }
    }

    /** Copies the raw log (previous segment, then current) to [out]; the caller closes [out]. */
    fun copyTo(out: OutputStream) {
        synchronized(lock) {
            for (segment in listOf(previous, current)) {
                if (segment.isFile) segment.inputStream().use { it.copyTo(out) }
            }
        }
    }

    private fun openWriter(): FileOutputStream {
        writer?.let { return it }
        Files.createDirectories(dir.toPath())
        currentBytes = current.length()
        if (currentBytes > 0 && !endsWithNewline(current)) {
            FileOutputStream(current, true).use { it.write(NEWLINE.code) }
            currentBytes += 1
        }
        return FileOutputStream(current, true).also { writer = it }
    }

    private fun rotate(): FileOutputStream {
        closeWriter()
        Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return openWriter()
    }

    private fun closeWriter() {
        writer?.close()
        writer = null
    }

    private fun endsWithNewline(file: File): Boolean =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() == NEWLINE.code
        }

    private fun segmentLines(file: File): List<String> =
        if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()

    companion object {
        const val MAX_SEGMENT_BYTES = 256L * 1024
        const val CURRENT = "app.log"
        const val PREVIOUS = "app.log.1"
        private const val NEWLINE = '\n'
    }
}
