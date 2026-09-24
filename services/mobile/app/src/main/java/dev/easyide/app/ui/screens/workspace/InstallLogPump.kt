package dev.easyide.app.ui.screens.workspace

import android.os.Handler
import android.os.Looper

/**
 * Feeds install progress into a terminal's screen buffer as if it were real process output -
 * not `session.write()`, which is stdin and would be typed *at* the shell. The progress
 * callback fires from a background (IO) dispatcher, but `TerminalEmulator`/`TerminalBuffer` are
 * not thread-safe (Termux's own pty-read loop only ever touches them from the main thread via
 * its `Handler`), so lines are queued here and handed to the main thread in one post per
 * [FLUSH_MS] window - one post per line flooded the main looper during apt/dpkg output.
 *
 * @param terminals the current tabs; the flush falls back to any open tab if the one active at
 *   install start was since closed.
 */
internal class InstallLogPump(private val terminals: () -> List<PtyTerminalTab>) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()
    private var targetTabId: String? = null

    private val flush = Runnable {
        val (tabId, text) = synchronized(pending) {
            (targetTabId to pending.toString()).also { pending.setLength(0) }
        }
        val tabs = terminals()
        val tab = tabs.find { it.id == tabId } ?: tabs.firstOrNull() ?: return@Runnable
        val bytes = text.toByteArray()
        tab.session.emulator?.append(bytes, bytes.size)
        tab.client.onTextChanged(tab.session)
    }

    fun append(tabId: String?, line: String) {
        val firstInWindow = synchronized(pending) {
            targetTabId = tabId
            val wasEmpty = pending.isEmpty()
            pending.append(line).append("\r\n")
            wasEmpty
        }
        if (firstInWindow) mainHandler.postDelayed(flush, FLUSH_MS)
    }

    fun stop() = mainHandler.removeCallbacks(flush)

    private companion object {
        /** Batching window for install output; matches TerminalProcess's own flush cadence. */
        const val FLUSH_MS = 60L
    }
}
