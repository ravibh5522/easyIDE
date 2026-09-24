package dev.easyide.app.diagnostics

import java.io.File
import java.io.OutputStream

/**
 * The app's [LogSink]: a [RollingLogFile] in [dir] stamped with [clock]. This is the one log
 * a bug report carries; subsystems that own their own log port are bridged onto it
 * ([ExtensionLogBridge], [LspStatusBridge]).
 *
 * [log] never throws. A full or unwritable disk is a real I/O boundary, and losing a log
 * line is strictly better than crashing the app while trying to record something else, so
 * failures are dropped here and nowhere deeper.
 */
class AppLog(
    dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : LogSink {
    private val file = RollingLogFile(dir)

    override fun log(level: LogLevel, source: LogSource, message: String) {
        try {
            file.append(LogLine(clock(), level, source, message))
        } catch (dropped: Exception) {
            // Deliberately dropped: see the class comment.
        }
    }

    /** The newest WARN and ERROR lines, oldest first, for the "recent problems" section. */
    fun errors(limit: Int): List<LogLine> =
        file.readAll().filter { it.level >= LogLevel.WARN }.takeLast(limit.coerceAtLeast(0))

    fun tail(n: Int): List<LogLine> = file.tail(n)

    fun readAll(): List<LogLine> = file.readAll()

    fun sizeBytes(): Long = file.sizeBytes()

    /** Deletes the whole log; later lines start a new one. */
    fun clear() = file.clear()

    /** Copies the raw log text to [out] for export; the caller closes [out]. */
    fun copyTo(out: OutputStream) = file.copyTo(out)
}
