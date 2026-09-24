package dev.easyide.app.ui.screens.workspace.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.SemanticStyler
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
internal class DocumentHighlighter(
    val grammar: Grammar,
    /**
     * Per-line tokenize budget, 0 for none. Extension grammars get one (threat-model
     * M-22): a line over budget stops colouring for the rest of this document, since the
     * tokenizer cannot be interrupted mid-line and a pathological grammar would otherwise
     * stall every pass.
     */
    private val lineBudgetNanos: Long = 0,
    private val onOverBudget: () -> Unit = {},
) {
    /** Set once a line blew [lineBudgetNanos]; the rest of the document stays plain. */
    private var overBudget = false

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

    /**
     * Tokenize far enough to have spans for every line up to and including [through].
     *
     * [checkCancelled] runs between lines so a superseded pass stops instead of
     * holding the highlighter lock while the fresh pass waits. Each line's state
     * and spans are appended together, so stopping between lines leaves the
     * cache consistent.
     */
    fun tokenizeThrough(through: Int, checkCancelled: () -> Unit = {}) {
        val target = through.coerceAtMost(lines.lastIndex)
        if (target < 0) return

        var index = endStates.size
        var state = if (index == 0) initialState() else endStates[index - 1]

        while (index <= target) {
            checkCancelled()
            val line = lines[index]
            val started = if (lineBudgetNanos > 0) System.nanoTime() else 0L
            if (overBudget) {
                lineSpans.add(emptyList())
            } else if (line.length > MAX_LINE_LENGTH) {
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
            if (lineBudgetNanos > 0 && !overBudget && System.nanoTime() - started > lineBudgetNanos) {
                overBudget = true
                onOverBudget()
            }
            endStates.add(state)
            index++
        }
    }

    /**
     * Style only lines `[from, to]`, with [semantic] tokens (if any) painted over the
     * grammar's colours.
     *
     * The editor is one text field holding the whole document, so the text has
     * to be complete - but the *spans* do not. Restricting them to the visible
     * window keeps the styled-span count flat (hundreds) no matter how long the
     * file is, which is what a large `AnnotatedString` actually chokes on.
     */
    fun annotate(
        text: String,
        colors: SyntaxColors,
        from: Int,
        to: Int,
        checkCancelled: () -> Unit = {},
        semantic: SemanticPaint? = null,
    ): AnnotatedString {
        val first = from.coerceAtLeast(0)
        val last = to.coerceAtMost(lines.lastIndex)
        if (first > last) return AnnotatedString(text)

        tokenizeThrough(last, checkCancelled)

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
            // Added after the TextMate spans of the line: a later SpanStyle wins where they
            // overlap, so the server's classification replaces the grammar's colour there
            // and TextMate still colours everything the server left out (augmentsSyntaxTokens).
            semantic?.addLine(builder, i, lineStart, lines[i].length)
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

/** Semantic tokens for one highlight pass: per-line spans already shifted onto the text, and their styling. */
class SemanticPaint(private val lines: Array<List<SemanticSpan>?>, private val styler: SemanticStyler) {

    companion object {
        /** [overlay] shifted onto [text] and styled with [colors] for [languageId]; null without an overlay. */
        fun of(overlay: SemanticOverlay?, text: String, colors: EditorColors, languageId: String?): SemanticPaint? =
            overlay?.let { SemanticPaint(it.linesFor(text), SemanticStyler(colors.syntax, colors.semantic, languageId)) }
    }

    fun addLine(builder: AnnotatedString.Builder, line: Int, lineStart: Int, lineLength: Int) {
        val spans = lines.getOrNull(line) ?: return
        for (span in spans) {
            val end = minOf(span.end, lineLength)
            if (end <= span.start) continue
            val style = styler.styleFor(span.key) ?: continue
            builder.addStyle(style, lineStart + span.start, lineStart + end)
        }
    }
}
