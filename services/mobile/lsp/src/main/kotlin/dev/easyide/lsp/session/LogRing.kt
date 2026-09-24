package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy

/**
 * Bounded, thread-safe line buffer: the session's log (stderr, `window/logMessage`, state
 * transitions, timeouts, trace) and its stderr tail for `Failed` states. Written from the
 * stderr reader on IO and from the session dispatcher, hence the lock.
 */
class LogRing(private val capacity: Int) {
    private val lines = ArrayDeque<String>(capacity)

    fun add(line: String) {
        val clipped = if (line.length > LspPolicy.MAX_LOG_LINE_CHARS) line.substring(0, LspPolicy.MAX_LOG_LINE_CHARS) else line
        synchronized(lines) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(clipped)
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
