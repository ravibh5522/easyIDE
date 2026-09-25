package dev.easyide.app.ui.screens.extensions

import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.manifest.ExtensionId
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionLogTest {

    private fun entry(at: Long, id: String?, level: LogLevel, message: String) =
        TimedLogEntry(at, LogEntry(id?.let { ExtensionId.parse(it) }, level, message))

    private val log = listOf(
        entry(3, "acme.docker", LogLevel.ERROR, "activation failed"),
        entry(2, null, LogLevel.INFO, "runtime started"),
        entry(1, "acme.tool", LogLevel.WARN, "slow"),
    )

    @Test fun `a page keeps only the lines its extension wrote`() {
        assertEquals(listOf("activation failed"), logFor(log, "acme.docker").map { it.entry.message })
        assertEquals(emptyList<TimedLogEntry>(), logFor(log, "acme.missing"))
    }

    @Test fun `a line names its extension only when the log is not already about one`() {
        assertEquals("10:00 ERROR [acme.docker] activation failed", logLine(log[0], "10:00", showId = true))
        assertEquals("10:00 ERROR activation failed", logLine(log[0], "10:00", showId = false))
        assertEquals("10:00 INFO runtime started", logLine(log[1], "10:00", showId = true))
    }

    @Test fun `copy text is the shown lines newest first with the injected clock`() {
        val text = logText(log, showId = true) { "t$it" }
        assertEquals("t3 ERROR [acme.docker] activation failed\nt2 INFO runtime started\nt1 WARN [acme.tool] slow", text)
    }

    @Test fun `copy text is capped at the lines shown`() {
        val many = (1..LOG_LINES_SHOWN + 20).map { entry(it.toLong(), null, LogLevel.INFO, "m$it") }
        assertEquals(LOG_LINES_SHOWN, logText(many, showId = false) { "" }.lines().size)
    }

    @Test fun `errors and warnings carry a signal tone and the rest stay neutral`() {
        assertEquals(Tone.Danger, levelTone(LogLevel.ERROR))
        assertEquals(Tone.Warning, levelTone(LogLevel.WARN))
        assertEquals(Tone.Neutral, levelTone(LogLevel.INFO))
        assertEquals(Tone.Neutral, levelTone(LogLevel.DEBUG))
    }
}
