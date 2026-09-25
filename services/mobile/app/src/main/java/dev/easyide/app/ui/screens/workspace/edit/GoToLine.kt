package dev.easyide.app.ui.screens.workspace.edit

/** A 0-based line and column. */
data class GoToTarget(val line: Int, val column: Int)

/** The "Go to line" input: `12` or `12:5` (1-based, as line numbers are shown). */
object GoToLine {

    /**
     * The target for [input] in a document of [lineCount] lines, or null when it is not a
     * positive number. A line past the end lands on the last line rather than failing: the
     * user's intent is "the bottom".
     */
    fun parse(input: String, lineCount: Int): GoToTarget? {
        val parts = input.trim().split(':', ',').map(String::trim)
        if (parts.size > 2) return null
        val line = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val column = if (parts.size == 2) parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null else 1
        return GoToTarget(line.coerceAtMost(lineCount.coerceAtLeast(1)) - 1, column - 1)
    }

    /** The offset of [target] in [text]; a column past the end of its line lands at the line's end. */
    fun offsetOf(text: String, target: GoToTarget): Int {
        var lineStart = 0
        repeat(target.line) {
            val newline = text.indexOf('\n', lineStart)
            if (newline < 0) return text.length
            lineStart = newline + 1
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        return (lineStart + target.column).coerceAtMost(lineEnd)
    }
}
