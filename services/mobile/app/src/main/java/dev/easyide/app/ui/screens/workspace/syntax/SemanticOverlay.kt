package dev.easyide.app.ui.screens.workspace.syntax

import dev.easyide.lsp.protocol.SemanticToken

/** A semantic token's classification; interned per overlay so styling can memoise by identity. */
data class SemanticKey(val type: String, val modifiers: Set<String>)

/** One semantic run inside a line, UTF-16 offsets relative to the line start (like [StyledSpan]). */
class SemanticSpan(val start: Int, val end: Int, val key: SemanticKey)

/**
 * Semantic tokens of one document laid out per line, for the renderer to paint over TextMate
 * colouring (lsp-features.md 4.13). Immutable; [text] is the document text the tokens were
 * computed for.
 *
 * Staleness is ShiftAdjusted per line: [linesFor] maps the overlay onto newer text by the
 * common line prefix and suffix - lines above the first changed line keep their spans, the
 * changed lines have none until the next result, lines below move with the text. That is the
 * same region `DocumentHighlighter.setContent` invalidates, so both layers agree.
 */
class SemanticOverlay private constructor(
    val text: String,
    private val lineTexts: List<String>,
    private val lines: Array<List<SemanticSpan>?>,
) {
    /** The (text, lines) of the last [linesFor] call; the renderer asks repeatedly for one text. */
    @Volatile private var memo: Pair<String, Array<List<SemanticSpan>?>>? = null

    val lineCount: Int get() = lines.size

    fun spansOn(line: Int): List<SemanticSpan>? = lines.getOrNull(line)

    /** Spans per line of [current], shifted from [text]; null entries have no semantic colour. */
    fun linesFor(current: String): Array<List<SemanticSpan>?> {
        if (current === text || current == text) return lines
        memo?.let { (t, l) -> if (t === current) return l }
        val next = current.split('\n')
        var prefix = 0
        val limit = minOf(lineTexts.size, next.size)
        while (prefix < limit && lineTexts[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && lineTexts[lineTexts.size - 1 - suffix] == next[next.size - 1 - suffix]) suffix++
        val out = arrayOfNulls<List<SemanticSpan>>(next.size)
        for (i in 0 until prefix) out[i] = lines[i]
        for (k in 1..suffix) out[next.size - k] = lines[lines.size - k]
        memo = current to out
        return out
    }

    companion object {
        /**
         * Lays [tokens] (absolute line, UTF-16 column) out per line of [text]. Tokens past the
         * end of their line are clipped, ones on lines past the end dropped, and at most
         * [maxTokens] are kept so a pathological server cannot exhaust memory.
         */
        fun build(text: String, tokens: List<SemanticToken>, maxTokens: Int = Int.MAX_VALUE): SemanticOverlay {
            val lineTexts = text.split('\n')
            val lines = arrayOfNulls<MutableList<SemanticSpan>>(lineTexts.size)
            val keys = HashMap<SemanticKey, SemanticKey>()
            var kept = 0
            for (t in tokens) {
                if (kept >= maxTokens) break
                val lineText = lineTexts.getOrNull(t.line) ?: continue
                val end = minOf(t.start + t.length, lineText.length)
                if (t.start < 0 || end <= t.start) continue
                val key = SemanticKey(t.type, t.modifiers).let { keys.getOrPut(it) { it } }
                (lines[t.line] ?: ArrayList<SemanticSpan>().also { lines[t.line] = it }) += SemanticSpan(t.start, end, key)
                kept++
            }
            @Suppress("UNCHECKED_CAST") // MutableList<T>? is a List<T>?; the array is never written again.
            return SemanticOverlay(text, lineTexts, lines as Array<List<SemanticSpan>?>)
        }
    }
}
