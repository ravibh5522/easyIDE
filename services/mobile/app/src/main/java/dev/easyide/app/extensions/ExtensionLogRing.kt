package dev.easyide.app.extensions

import android.util.Log
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One Extension Log line with the time it was written. */
data class TimedLogEntry(val atMs: Long, val entry: LogEntry)

/**
 * The Extension Log (ECO-31): a bounded in-memory ring of activation errors, action
 * failures and runtime warnings, shown on the Extensions screen. Entries arrive already
 * redacted (R-SEC-17). Mirrored to logcat so a crash report carries the same lines.
 *
 * Thread-safe: the runtime appends from its own lane and from action coroutines.
 */
class ExtensionLogRing(
    private val capacity: Int = ExtensionUiPolicy.LOG_RING_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mirror: (LogEntry) -> Unit = ::toLogcat,
) : ExtensionLog {

    private val state = MutableStateFlow<List<TimedLogEntry>>(emptyList())
    val entries: StateFlow<List<TimedLogEntry>> = state.asStateFlow()

    override fun append(entry: LogEntry) {
        mirror(entry)
        val timed = TimedLogEntry(clock(), entry)
        state.update { current -> (current + timed).takeLast(capacity) }
    }

    fun clear() { state.value = emptyList() }

    private companion object {
        const val TAG = "ExtensionLog"

        fun toLogcat(entry: LogEntry) {
            val text = entry.extensionId?.let { "[${it.value}] ${entry.message}" } ?: entry.message
            when (entry.level) {
                LogLevel.ERROR -> Log.e(TAG, text)
                LogLevel.WARN -> Log.w(TAG, text)
                LogLevel.INFO -> Log.i(TAG, text)
                LogLevel.DEBUG, LogLevel.TRACE -> Log.d(TAG, text)
            }
        }
    }
}
