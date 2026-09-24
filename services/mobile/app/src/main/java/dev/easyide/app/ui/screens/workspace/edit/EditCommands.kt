package dev.easyide.app.ui.screens.workspace.edit

/**
 * Editor commands that act on a [TextState] rather than on a keystroke. Each
 * is UI-agnostic so a command registry, a key-row button or a keybinding can
 * call it and hand the result back to whichever surface owns the buffer.
 */
object EditCommands {

    /** How far a bracket search walks before giving up, so a stray `{` in a huge file stays cheap. */
    const val MAX_BRACKET_SCAN_CHARS = 20_000

    /**
     * Toggles the language's line comment on every line the selection touches,
     * falling back to wrapping the lines in its block comment (HTML, CSS,
     * Markdown have no line comment). Returns [state] unchanged when the
     * language defines neither.
     */
    fun toggleComment(state: TextState, config: LanguageConfig): TextState {
        val lines = selectedLines(state)
        val edits = config.lineComment?.let { lineCommentEdits(state.text, lines, it) }
            ?: config.blockComment?.let { blockCommentEdits(state.text, lines, it) }
            ?: return state
        return apply(state, edits)
    }

    /**
     * The bracket touching [caret] (the one before it first, as VS Code does)
     * and its partner, as two offsets; null when there is none within
     * [MAX_BRACKET_SCAN_CHARS]. Brackets inside strings and comments are
     * counted too - that is the price of not needing a parse.
     */
    fun matchingBracket(text: String, caret: Int, brackets: List<CharPair>): Pair<Int, Int>? {
        for (at in intArrayOf(caret - 1, caret)) {
            if (at !in text.indices) continue
            val c = text[at]
            for (pair in brackets) {
                if (pair.open.length != 1 || pair.close.length != 1) continue
                val open = pair.open[0]
                val close = pair.close[0]
                val match = when (c) {
                    open -> scan(text, at, open, close, 1)
                    close -> scan(text, at, close, open, -1)
                    else -> continue
                }
                if (match != null) return at to match
            }
        }
        return null
    }

    private fun scan(text: String, from: Int, self: Char, partner: Char, step: Int): Int? {
        var depth = 0
        var i = from
        var walked = 0
        while (i in text.indices && walked <= MAX_BRACKET_SCAN_CHARS) {
            when (text[i]) {
                self -> depth++
                partner -> if (--depth == 0) return i
            }
            i += step
            walked++
        }
        return null
    }

    /** One replacement: [length] chars at [at] become [insert]. */
    private data class Edit(val at: Int, val length: Int, val insert: String)

    private class Line(val start: Int, val end: Int, val indent: Int)

    /**
     * Lines touched by the selection. A selection ending at column 0 does not
     * include that line - selecting whole lines by dragging to the next one
     * is the common case.
     */
    private fun selectedLines(state: TextState): List<Line> {
        val text = state.text
        var start = text.lastIndexOf('\n', state.min - 1) + 1
        val lastLineEnd = if (state.max > state.min && text.getOrNull(state.max - 1) == '\n') state.max - 1 else state.max
        val out = ArrayList<Line>()
        while (true) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var indent = start
            while (indent < end && (text[indent] == ' ' || text[indent] == '\t')) indent++
            out.add(Line(start, end, indent))
            if (end >= lastLineEnd || end == text.length) break
            start = end + 1
        }
        return out
    }

    private fun lineCommentEdits(text: String, lines: List<Line>, token: String): List<Edit> {
        val content = lines.filter { it.indent < it.end }.ifEmpty { lines }
        val allCommented = content.all { text.startsWith(token, it.indent) }
        if (allCommented) {
            return content.map {
                val spaced = text.getOrNull(it.indent + token.length) == ' '
                Edit(it.indent, token.length + if (spaced) 1 else 0, "")
            }
        }
        // One column for the whole block, so the comment markers line up.
        val column = content.minOf { it.indent - it.start }
        return content.map { Edit(it.start + column, 0, "$token ") }
    }

    private fun blockCommentEdits(text: String, lines: List<Line>, tokens: Pair<String, String>): List<Edit> {
        val (open, close) = tokens
        val from = lines.first().indent
        var to = lines.last().end
        while (to > from && text[to - 1].isWhitespace()) to--
        val body = text.substring(from, to)
        if (body.length >= open.length + close.length && body.startsWith(open) && body.endsWith(close)) {
            val openLen = open.length + if (text.getOrNull(from + open.length) == ' ') 1 else 0
            val closeLen = close.length + if (text.getOrNull(to - close.length - 1) == ' ') 1 else 0
            return listOf(Edit(from, openLen, ""), Edit(to - closeLen, closeLen, ""))
        }
        return listOf(Edit(from, 0, "$open "), Edit(to, 0, " $close"))
    }

    /** Applies ascending, non-overlapping [edits] and carries the selection through them. */
    private fun apply(state: TextState, edits: List<Edit>): TextState {
        val text = state.text
        val out = StringBuilder(text.length + edits.sumOf { it.insert.length })
        var cursor = 0
        for (e in edits) {
            out.append(text, cursor, e.at).append(e.insert)
            cursor = e.at + e.length
        }
        out.append(text, cursor, text.length)
        return TextState(
            out.toString(),
            map(state.selectionStart, edits, state.collapsed || state.selectionStart > state.selectionEnd),
            map(state.selectionEnd, edits, state.collapsed || state.selectionEnd > state.selectionStart),
        )
    }

    /**
     * Where [offset] lands after [edits]. An insertion exactly at the offset
     * pushes it right only when [pushed]: a selection's end moves past a new
     * comment marker, its start stays in front of it so the marker is selected.
     */
    private fun map(offset: Int, edits: List<Edit>, pushed: Boolean): Int {
        var shift = 0
        for (e in edits) {
            when {
                offset > e.at + e.length || (offset == e.at + e.length && (e.length > 0 || pushed)) ->
                    shift += e.insert.length - e.length
                offset > e.at -> return e.at + shift + e.insert.length
                else -> break
            }
        }
        return offset + shift
    }
}
