package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure state transitions for the terminal tabs. Kept out of the ViewModel so
 * the rules (which tab is active after a close, how history stepping wraps)
 * are readable and testable without a coroutine scope.
 */
internal object WorkspaceTerminals {

    const val MAX_LINES = 500
    const val MAX_LINE_LENGTH = 2_000

    fun newSession(index: Int, id: String, banner: String): TerminalSession =
        TerminalSession(
            id = id,
            title = "sh ${index + 1}",
            lines = listOf(TerminalLine(banner, isCommand = false)),
        )

    fun updateSession(
        sessions: List<TerminalSession>,
        sessionId: String,
        transform: (TerminalSession) -> TerminalSession,
    ): List<TerminalSession> = sessions.map { if (it.id == sessionId) transform(it) else it }

    fun startCommand(session: TerminalSession, command: String): TerminalSession =
        session.appended(listOf(TerminalLine("$ $command", isCommand = true)), MAX_LINES).copy(
            input = TextFieldValue(),
            isRunning = true,
            // Consecutive duplicates add nothing when stepping back.
            history = if (session.history.lastOrNull() == command) session.history else session.history + command,
            historyCursor = null,
        )

    /** Steps back one entry; stops at the oldest rather than wrapping around. */
    fun historyUp(session: TerminalSession): TerminalSession {
        if (session.history.isEmpty()) return session
        val next = (session.historyCursor ?: session.history.size) - 1
        if (next < 0) return session
        return session.copy(input = TerminalInput.atEnd(session.history[next]), historyCursor = next)
    }

    /** Steps forward; past the newest entry lands back on an empty prompt. */
    fun historyDown(session: TerminalSession): TerminalSession {
        val next = (session.historyCursor ?: return session) + 1
        if (next >= session.history.size) {
            return session.copy(input = TextFieldValue(), historyCursor = null)
        }
        return session.copy(input = TerminalInput.atEnd(session.history[next]), historyCursor = next)
    }

    /**
     * Closing the active tab moves focus to a neighbour; the last tab is never
     * closed, so the panel always has something to show.
     */
    fun close(
        sessions: List<TerminalSession>,
        activeId: String?,
        sessionId: String,
    ): Pair<List<TerminalSession>, String?> {
        if (sessions.size <= 1) return sessions to activeId
        val remaining = sessions.filterNot { it.id == sessionId }
        val nextActive = if (activeId == sessionId) remaining.lastOrNull()?.id else activeId
        return remaining to nextActive
    }
}
