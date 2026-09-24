package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.sandbox.git.GitStatus

/** A caret position, 1-based like every editor's status bar. */
data class CaretPosition(val line: Int, val column: Int)

enum class LineEnding(val label: String) { LF("LF"), CRLF("CRLF") }

/** The git part of the status bar: branch, commits ahead/behind its upstream, changed-file count. */
data class GitSummary(val branch: String, val ahead: Int, val behind: Int, val changed: Int) {
    companion object {
        /** Null for a folder that is not a repository or whose first read is still in flight. */
        fun of(isRepository: Boolean, status: GitStatus?): GitSummary? {
            if (!isRepository || status == null || status.branch.isEmpty()) return null
            return GitSummary(status.branch, status.ahead, status.behind, status.totalChanges)
        }
    }
}

/** The active file's facts for the status bar. Everything derives from the buffer and its selection. */
object StatusFacts {

    /**
     * The 1-based line and column of [offset]. Offsets past the text (the buffer changed
     * under a stale selection for a frame) clamp to its end. Cost is O(offset), paid per
     * caret move, which is what the editor already pays to lay the line out.
     */
    fun caret(text: String, offset: Int): CaretPosition {
        val at = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until at) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return CaretPosition(line, at - lineStart + 1)
    }

    /** CRLF when the first line ends in one, else LF. Reads one line, not the file. */
    fun lineEnding(text: String): LineEnding {
        val newline = text.indexOf('\n')
        return if (newline > 0 && text[newline - 1] == '\r') LineEnding.CRLF else LineEnding.LF
    }
}
