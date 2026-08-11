package dev.easyide.app.ui.screens.workspace.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.easyide.app.ui.theme.SyntaxColors
import dev.easyide.app.ui.theme.SyntaxRole
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.tokenize.StateStack

/** One coloured run inside a line, offsets relative to that line's start. */
internal class StyledSpan(val start: Int, val end: Int, val role: SyntaxRole)

/**
 * Incremental, viewport-driven tokenizer state for a single open file.
 *
 * TextMate tokenizing is a left-to-right state machine: line N's result depends
 * on the state left behind by line N-1. Re-running it from line 0 on every
 * keystroke is what limited the editor to small files. This keeps two things
 * per line - the tokenizer state it *ended* in, and the spans it produced - so
 * that:
 *
 *  - **typing** only invalidates from the first changed line onward. Lines above
 *    the edit keep their state and their spans;
 *  - **opening** only tokenizes down to what is on screen. The rest is computed
 *    lazily as the user scrolls;
 *  - **an edit above the viewport** re-tokenizes forward only until the state
 *    machine converges back onto the state it had before. In practice that is a
 *    handful of lines, because most edits do not open or close a construct that
 *    spans lines.
 *
 * This is the same shape VS Code uses, and it is what makes file size stop
 * mattering for the cost of an edit.
 */
internal class DocumentHighlighter(private val grammar: Grammar) {

    private var lines: List<String> = emptyList()
    private var lineStarts = IntArray(0)

    /** `endStates[i]` is the tokenizer state after line `i`; parallel to [lineSpans]. */
    private val endStates = ArrayList<StateStack>()
    private val lineSpans = ArrayList<List<StyledSpan>>()

    /** Where the seed state comes from; cheap, and avoids naming the state type. */
    private fun initialState(): StateStack = grammar.tokenizeLine("", null).ruleStack

    /**
     * Point the highlighter at new buffer content, keeping every line above the
     * first change. Returns the number of lines whose cached tokens survived.
     */
    fun setContent(text: String): Int {
        val next = text.split('\n')

        var reusable = 0
        val limit = minOf(lines.size, next.size, endStates.size)
        while (reusable < limit && lines[reusable] == next[reusable]) reusable++

        if (endStates.size > reusable) {
            endStates.subList(reusable, endStates.size).clear()
            lineSpans.subList(reusable, lineSpans.size).clear()
        }

        lines = next
        lineStarts = IntArray(next.size)
        var offset = 0
        for (i in next.indices) {
            lineStarts[i] = offset
            offset += next[i].length + 1
        }
        return reusable
    }

    /** Tokenize far enough to have spans for every line up to and including [through]. */
    fun tokenizeThrough(through: Int) {
        val target = through.coerceAtMost(lines.lastIndex)
        if (target < 0) return

        var index = endStates.size
        var state = if (index == 0) initialState() else endStates[index - 1]

        while (index <= target) {
            val line = lines[index]
            if (line.length > MAX_LINE_LENGTH) {
                // Advance the machine on a truncated copy so later lines stay
                // correct, but do not try to colour a minified monster.
                state = grammar.tokenizeLine(line.take(MAX_LINE_LENGTH), state).ruleStack
                lineSpans.add(emptyList())
            } else {
                val result = grammar.tokenizeLine(line, state)
                state = result.ruleStack
                lineSpans.add(
                    result.tokens.mapNotNull { token ->
                        val role = ScopeRules.roleFor(token.scopes)
                        if (role == SyntaxRole.PLAIN) return@mapNotNull null
                        val end = token.endIndex.coerceAtMost(line.length)
                        if (end <= token.startIndex) null else StyledSpan(token.startIndex, end, role)
                    },
                )
            }
            endStates.add(state)
            index++
        }
    }

    /**
     * Style only lines `[from, to]`.
     *
     * The editor is one text field holding the whole document, so the text has
     * to be complete - but the *spans* do not. Restricting them to the visible
     * window keeps the styled-span count flat (hundreds) no matter how long the
     * file is, which is what a large `AnnotatedString` actually chokes on.
     */
    fun annotate(text: String, colors: SyntaxColors, from: Int, to: Int): AnnotatedString {
        val first = from.coerceAtLeast(0)
        val last = to.coerceAtMost(lines.lastIndex)
        if (first > last) return AnnotatedString(text)

        tokenizeThrough(last)

        val builder = AnnotatedString.Builder(text)
        for (i in first..last) {
            val lineStart = lineStarts[i]
            for (span in lineSpans[i]) {
                builder.addStyle(
                    SpanStyle(color = colors[span.role]),
                    lineStart + span.start,
                    lineStart + span.end,
                )
            }
        }
        return builder.toAnnotatedString()
    }

    companion object {
        /**
         * One pathological minified line can make a backtracking regex run for a
         * very long time; past this it is advanced but not coloured.
         */
        const val MAX_LINE_LENGTH = 2_000
    }
}
