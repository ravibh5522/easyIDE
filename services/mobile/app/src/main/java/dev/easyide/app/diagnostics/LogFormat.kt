package dev.easyide.app.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One entry of the app log, as written to and read back from disk. */
data class LogLine(
    val atMs: Long,
    val level: LogLevel,
    val source: LogSource,
    val message: String,
)

/**
 * The on-disk line format: `<ISO-8601 UTC millis> <L>/<tag> <message>`, for example
 * `2026-09-24T12:00:00.123Z E/sandbox proot exited with 1`.
 *
 * One entry is exactly one line, so a log stays greppable, a crash mid-write can damage at
 * most the last line, and rotation can cut on any line boundary. Backslash, CR, LF and the
 * other control characters in a message are escaped (`\\`, `\r`, `\n`, `\uXXXX`) and
 * decoded back, so a stack trace passed as one message survives as one entry.
 *
 * Decoding is total: [decode] returns null for a line that is not in this format (a torn
 * write, a hand-edited file) and never throws. Callers skip those lines. Keeping a garbage
 * line as a fake APP/INFO entry would give it a timestamp it does not have and pollute the
 * "recent problems" view, so dropping it is the honest choice; the raw bytes are still in the
 * file that "Export" copies verbatim.
 */
object LogFormat {
    /** Longer messages are cut so one runaway line cannot fill a whole segment. */
    const val MAX_MESSAGE_CHARS = 8 * 1024

    private const val TRUNCATION_MARK = "...[truncated]"

    /** Millisecond precision and a fixed width, so lines sort and align as text. */
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private const val TIME_LENGTH = 24

    fun formatTime(atMs: Long): String = TIME.format(Instant.ofEpochMilli(atMs))

    fun encode(line: LogLine): String =
        "${formatTime(line.atMs)} ${line.level.letter}/${line.source.tag} ${escape(clip(line.message))}"

    fun decode(text: String): LogLine? {
        // "<time> <L>/<tag> <message>": the header is fixed-width up to the tag, which ends at a space.
        val levelAt = TIME_LENGTH + 1
        if (text.length < levelAt + 3 || text[TIME_LENGTH] != ' ' || text[levelAt + 1] != '/') return null
        val level = LogLevel.ofLetter(text[levelAt]) ?: return null
        val tagEnd = text.indexOf(' ', levelAt + 2).takeIf { it >= 0 } ?: text.length
        val source = LogSource.ofTag(text.substring(levelAt + 2, tagEnd)) ?: return null
        val atMs = parseTime(text.substring(0, TIME_LENGTH)) ?: return null
        val message = if (tagEnd < text.length) unescape(text.substring(tagEnd + 1)) else ""
        return LogLine(atMs, level, source, message)
    }

    // The text comes off disk, so a malformed timestamp is an expected input, not a bug.
    private fun parseTime(text: String): Long? =
        runCatching { Instant.from(TIME.parse(text)).toEpochMilli() }.getOrNull()

    private fun clip(message: String): String {
        if (message.length <= MAX_MESSAGE_CHARS) return message
        // Do not cut between the two halves of a surrogate pair.
        val cut = if (message[MAX_MESSAGE_CHARS - 1].isHighSurrogate()) MAX_MESSAGE_CHARS - 1 else MAX_MESSAGE_CHARS
        return message.substring(0, cut) + TRUNCATION_MARK
    }

    private fun escape(message: String): String {
        if (message.none { it == '\\' || it < ' ' }) return message
        val out = StringBuilder(message.length + ESCAPE_HEADROOM)
        for (c in message) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u").append("%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i += 1
            if (c != '\\' || i >= text.length) {
                out.append(c)
                continue
            }
            val next = text[i]
            i += 1
            when (next) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                'u' -> i = appendUnicode(text, i, out)
                else -> out.append(c).append(next)
            }
        }
        return out.toString()
    }

    /** Reads the four hex digits after `\u`; anything else is kept literally. Returns the new index. */
    private fun appendUnicode(text: String, start: Int, out: StringBuilder): Int {
        val end = start + UNICODE_DIGITS
        val digits = if (end <= text.length) text.substring(start, end) else ""
        if (digits.isEmpty() || digits.any { it.digitToIntOrNull(HEX_RADIX) == null }) {
            out.append("\\u")
            return start
        }
        out.append(digits.toInt(HEX_RADIX).toChar())
        return end
    }

    private const val ESCAPE_HEADROOM = 16
    private const val UNICODE_DIGITS = 4
    private const val HEX_RADIX = 16
}
