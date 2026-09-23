package dev.easyide.lsp.text

import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.TextEdit

/**
 * The shape of one flush's change, published so decorations shift instead of flickering
 * (lsp-features.md 3.3). Offsets are UTF-16 indices into the old text ([startOffset],
 * [oldEndOffset]) and the new text ([newEndOffset]).
 */
data class EditDelta(
    val startOffset: Int,
    val oldEndOffset: Int,
    val newEndOffset: Int,
    val startLine: Int,
    val oldEndLine: Int,
    val lineDelta: Int,
)

/** One incremental change: replace [range] of the old text with [text]. */
data class TextChange(val range: Range, val text: String, val delta: EditDelta)

/**
 * Prefix/suffix diff: one range change per flush with no edit history (arch.md 7.3). O(n)
 * in the unchanged ends, so typing into a 400 KB document costs one linear scan.
 */
object TextDiff {

    /** @return null when the texts are equal. */
    fun compute(old: String, new: String, oldIndex: LineIndex = LineIndex(old)): TextChange? {
        if (old == new) return null
        val max = minOf(old.length, new.length)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++

        // Never cut a surrogate pair or a CRLF: servers disagree about positions inside them.
        if (p > 0 && (old[p - 1].isHighSurrogate() || old[p - 1] == '\r')) p--
        val oldEndAt = old.length - s
        if (s > 0 && oldEndAt > 0 && (old[oldEndAt].isLowSurrogate() || (old[oldEndAt] == '\n' && old[oldEndAt - 1] == '\r'))) s--

        val oldEnd = old.length - s
        val newEnd = new.length - s
        val range = oldIndex.range(p, oldEnd)
        val inserted = new.substring(p, newEnd)
        val newEndLine = range.start.line + lineBreaks(inserted)
        val delta = EditDelta(
            startOffset = p,
            oldEndOffset = oldEnd,
            newEndOffset = newEnd,
            startLine = range.start.line,
            oldEndLine = range.end.line,
            lineDelta = newEndLine - range.end.line,
        )
        return TextChange(range, inserted, delta)
    }

    /** Line terminators in [s], counting `\r\n` once (LineIndex's definition). */
    private fun lineBreaks(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') n++
            if (c == '\r') {
                n++
                if (i + 1 < s.length && s[i + 1] == '\n') i++
            }
            i++
        }
        return n
    }
}

/** Applies LSP text edits to a string (closed-file edits, tests, format-on-save). */
object TextEdits {

    /**
     * Applies [edits] computed against [text]. Edits at the same start keep array order, as
     * the spec requires for several inserts at one position.
     *
     * @return null if two edits overlap (an invalid edit set is refused whole).
     */
    fun apply(text: String, edits: List<TextEdit>): String? {
        if (edits.isEmpty()) return text
        val index = LineIndex(text)
        val spans = edits.mapIndexed { i, e -> Span(index.offset(e.range.start), index.offset(e.range.end), e.newText, i) }
            .sortedWith(compareBy<Span> { it.start }.thenBy { it.order })
        val out = StringBuilder(text.length)
        var at = 0
        for (span in spans) {
            if (span.start < at) return null
            out.append(text, at, span.start).append(span.text)
            at = maxOf(at, span.end)
        }
        out.append(text, at, text.length)
        return out.toString()
    }

    private class Span(val start: Int, val end: Int, val text: String, val order: Int)
}
