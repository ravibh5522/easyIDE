package dev.easyide.app.diagnostics

import dev.easyide.app.extensions.ExtensionLogRing
import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.easyide.extensions.action.LogLevel as ExtLevel

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionLogBridgeTest {
    private fun entry(message: String, level: ExtLevel = ExtLevel.INFO, id: String? = null) =
        TimedLogEntry(0, LogEntry(id?.let { ExtensionId.parse(it) }, level, message))

    @Test fun `on first sight every entry is new`() {
        val list = listOf(entry("a"), entry("b"))
        assertEquals(list, ExtensionLogBridge.newEntries(null, list))
    }

    @Test fun `only the entries after the last one seen are new`() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        assertEquals(listOf(c), ExtensionLogBridge.newEntries(b, listOf(a, b, c)))
        assertEquals(emptyList<TimedLogEntry>(), ExtensionLogBridge.newEntries(c, listOf(a, b, c)))
    }

    @Test fun `the last entry is found by identity, so equal messages are not confused`() {
        val first = entry("same")
        val second = entry("same")
        assertEquals(listOf(second), ExtensionLogBridge.newEntries(first, listOf(first, second)))
        assertEquals(emptyList<TimedLogEntry>(), ExtensionLogBridge.newEntries(second, listOf(first, second)))
    }

    @Test fun `when the last seen entry was evicted every entry in the ring counts as new`() {
        val evicted = entry("evicted")
        val list = listOf(entry("x"), entry("y"))
        assertEquals(list, ExtensionLogBridge.newEntries(evicted, list))
    }

    @Test fun `a cleared ring yields nothing`() {
        assertEquals(emptyList<TimedLogEntry>(), ExtensionLogBridge.newEntries(entry("a"), emptyList()))
    }

    @Test fun `levels map onto app log levels`() {
        assertEquals(LogLevel.DEBUG, ExtensionLogBridge.level(ExtLevel.TRACE))
        assertEquals(LogLevel.DEBUG, ExtensionLogBridge.level(ExtLevel.DEBUG))
        assertEquals(LogLevel.INFO, ExtensionLogBridge.level(ExtLevel.INFO))
        assertEquals(LogLevel.WARN, ExtensionLogBridge.level(ExtLevel.WARN))
        assertEquals(LogLevel.ERROR, ExtensionLogBridge.level(ExtLevel.ERROR))
    }

    @Test fun `the message is prefixed with the extension id when there is one`() {
        assertEquals("[acme.demo] failed", ExtensionLogBridge.message(entry("failed", id = "acme.demo")))
        assertEquals("failed", ExtensionLogBridge.message(entry("failed")))
    }

    @Test fun `the ring is forwarded once per entry, across several emissions`() = runTest {
        val ring = ExtensionLogRing(capacity = 3, clock = { 1L }, mirror = {})
        val sink = ListSink()
        val id = ExtensionId.parse("acme.demo")
        ExtensionLogBridge.start(ring.entries, sink, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()

        ring.append(LogEntry(id, ExtLevel.WARN, "one"))
        ring.append(LogEntry(null, ExtLevel.ERROR, "two"))
        advanceUntilIdle()
        ring.append(LogEntry(null, ExtLevel.INFO, "three"))
        ring.append(LogEntry(null, ExtLevel.INFO, "four")) // evicts "one" from the 3-entry ring
        advanceUntilIdle()

        assertEquals(
            listOf(
                ListSink.Entry(LogLevel.WARN, LogSource.EXTENSION, "[acme.demo] one"),
                ListSink.Entry(LogLevel.ERROR, LogSource.EXTENSION, "two"),
                ListSink.Entry(LogLevel.INFO, LogSource.EXTENSION, "three"),
                ListSink.Entry(LogLevel.INFO, LogSource.EXTENSION, "four"),
            ),
            sink.entries,
        )
    }

    @Test fun `clearing the ring does not replay or lose later entries`() = runTest {
        val ring = ExtensionLogRing(capacity = 3, clock = { 1L }, mirror = {})
        val sink = ListSink()
        ExtensionLogBridge.start(ring.entries, sink, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        ring.append(LogEntry(null, ExtLevel.INFO, "before"))
        advanceUntilIdle()
        ring.clear()
        ring.append(LogEntry(null, ExtLevel.INFO, "after"))
        advanceUntilIdle()
        assertEquals(listOf("before", "after"), sink.entries.map { it.message })
    }
}
