package dev.easyide.app.diagnostics

import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.extensions.action.LogLevel as ExtensionLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Forwards the Extension Log ring (already redacted, R-SEC-17) to a [LogSink] as
 * [LogSource.EXTENSION] lines, so an extension's activation failure shows up in the exported
 * log next to the sandbox and language-server lines around it. The ring's owner is not edited:
 * this only reads its public `entries` flow.
 */
object ExtensionLogBridge {
    /**
     * Collects [entries] (`ExtensionLogRing.entries`) on [context] and logs every entry once.
     * File I/O happens per entry, hence the IO default rather than the caller's dispatcher.
     */
    fun start(
        entries: Flow<List<TimedLogEntry>>,
        sink: LogSink,
        scope: CoroutineScope,
        context: CoroutineContext = Dispatchers.IO,
    ): Job = scope.launch(context) {
        var lastSeen: TimedLogEntry? = null
        entries.collect { current ->
            for (fresh in newEntries(lastSeen, current)) {
                sink.log(level(fresh.entry.level), LogSource.EXTENSION, message(fresh))
            }
            lastSeen = current.lastOrNull() ?: lastSeen
        }
    }

    /**
     * The entries of [current] that come after [lastSeen]. The ring replaces its list on every
     * append and keeps the entry objects, so the last forwarded entry is found by identity
     * (two identical messages at the same millisecond are still distinct entries). When it is
     * not in the list (first emission, the ring was cleared, or more than a ring's capacity
     * was appended between two collections and it was evicted) every entry of [current] is
     * new. That last case loses the evicted entries: a burst larger than the ring's capacity
     * cannot be recovered from the ring alone, and the ring is bounded on purpose.
     */
    fun newEntries(lastSeen: TimedLogEntry?, current: List<TimedLogEntry>): List<TimedLogEntry> {
        val seenAt = if (lastSeen == null) -1 else current.indexOfLast { it === lastSeen }
        return current.subList(seenAt + 1, current.size)
    }

    fun level(level: ExtensionLogLevel): LogLevel = when (level) {
        ExtensionLogLevel.TRACE, ExtensionLogLevel.DEBUG -> LogLevel.DEBUG
        ExtensionLogLevel.INFO -> LogLevel.INFO
        ExtensionLogLevel.WARN -> LogLevel.WARN
        ExtensionLogLevel.ERROR -> LogLevel.ERROR
    }

    fun message(entry: TimedLogEntry): String =
        entry.entry.extensionId?.let { "[${it.value}] ${entry.entry.message}" } ?: entry.entry.message
}
