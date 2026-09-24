package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {
    @Test fun `a line is time, level slash tag, message`() {
        val line = LogLine(0, LogLevel.ERROR, LogSource.SANDBOX, "proot exited with 1")
        assertEquals("1970-01-01T00:00:00.000Z E/sandbox proot exited with 1", LogFormat.encode(line))
    }

    @Test fun `time keeps millisecond precision and a fixed width`() {
        assertEquals("1970-01-01T00:00:00.007Z", LogFormat.formatTime(7))
        assertEquals("1970-01-01T00:00:01.000Z", LogFormat.formatTime(1000))
    }

    @Test fun `encode then decode is the identity`() {
        for (source in LogSource.entries) for (level in LogLevel.entries) {
            val line = LogLine(1_700_000_123_456, level, source, "hello world")
            assertEquals(line, LogFormat.decode(LogFormat.encode(line)))
        }
    }

    @Test fun `newlines and control characters are escaped so one entry is one line`() {
        val message = "first\nsecond\r\nthird\tbell\u0007 back\\slash café"
        val encoded = LogFormat.encode(LogLine(5, LogLevel.WARN, LogSource.APP, message))
        assertFalse('\n' in encoded)
        assertFalse('\r' in encoded)
        assertEquals(message, LogFormat.decode(encoded)?.message)
    }

    @Test fun `a stack trace survives as one entry`() {
        val trace = RuntimeException("boom", IllegalStateException("cause")).stackTraceToString()
        val decoded = LogFormat.decode(LogFormat.encode(LogLine(1, LogLevel.ERROR, LogSource.APP, trace)))
        assertEquals(trace, decoded?.message)
    }

    @Test fun `an empty message round trips`() {
        val line = LogLine(9, LogLevel.DEBUG, LogSource.LSP, "")
        assertEquals(line, LogFormat.decode(LogFormat.encode(line)))
        assertEquals("", LogFormat.decode("1970-01-01T00:00:00.009Z D/lsp")?.message)
    }

    @Test fun `garbage decodes to null and never throws`() {
        val garbage = listOf(
            "",
            "   ",
            "not a log line at all",
            "1970-01-01T00:00:00.000Z",
            "1970-01-01T00:00:00.000Z X/app bad level",
            "1970-01-01T00:00:00.000Z I/nowhere unknown tag",
            "1970-01-01T00:00:00.000Z I-app wrong separator",
            "1970-13-45T00:00:00.000Z I/app impossible date",
            "1970-01-01T00:00:00.000ZZ I/app shifted",
            "1970-01-01T00:00:0",
            "\u0000\u0000\u0000",
        )
        for (text in garbage) assertNull("'$text'", LogFormat.decode(text))
    }

    @Test fun `unknown escapes are kept literally`() {
        val decoded = LogFormat.decode("1970-01-01T00:00:00.000Z I/app a\\qb \\u12 \\uZZZZ end\\")
        assertEquals("a\\qb \\u12 \\uZZZZ end\\", decoded?.message)
    }

    @Test fun `an over-long message is cut with a marker`() {
        val long = "y".repeat(LogFormat.MAX_MESSAGE_CHARS * 2)
        val decoded = LogFormat.decode(LogFormat.encode(LogLine(1, LogLevel.INFO, LogSource.APP, long)))
        assertNotNull(decoded)
        assertTrue(decoded!!.message.endsWith("...[truncated]"))
        assertEquals(LogFormat.MAX_MESSAGE_CHARS + "...[truncated]".length, decoded.message.length)
    }

    @Test fun `truncation does not split a surrogate pair`() {
        val emoji = "😀"
        val message = "z".repeat(LogFormat.MAX_MESSAGE_CHARS - 1) + emoji
        val decoded = LogFormat.decode(LogFormat.encode(LogLine(1, LogLevel.INFO, LogSource.APP, message)))
        assertFalse(decoded!!.message.any { it.isHighSurrogate() })
    }
}
