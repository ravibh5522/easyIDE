package dev.easyide.app.diagnostics

enum class LogLevel(val letter: Char) {
    DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');

    companion object {
        fun ofLetter(letter: Char): LogLevel? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * Which part of the app wrote a line. The tag is what the Diagnostics screen filters on and
 * what an exported log shows, so it is part of the on-disk format: never rename one.
 */
enum class LogSource(val tag: String) {
    APP("app"), SANDBOX("sandbox"), LSP("lsp"), EXTENSION("ext");

    companion object {
        fun ofTag(tag: String): LogSource? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Where log lines go. Subsystems that already own a log port (the extension log ring, the
 * language-server session log) are bridged onto this at the composition root instead of being
 * edited; new code that has something worth keeping for a bug report writes to it directly.
 *
 * Implementations must be safe to call from any thread and must not throw.
 */
fun interface LogSink {
    fun log(level: LogLevel, source: LogSource, message: String)
}
