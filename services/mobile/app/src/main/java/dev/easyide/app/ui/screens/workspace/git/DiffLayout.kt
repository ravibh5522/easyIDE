package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffLineKind

/**
 * One displayed diff line with its line numbers. [emphasis] is the span inside
 * [text] that differs from the paired line on the other side of a change, so a
 * one-word edit is visible without reading two whole lines.
 */
data class DiffRow(
    val kind: DiffLineKind,
    val text: String,
    val oldNo: Int?,
    val newNo: Int?,
    val emphasis: IntRange? = null,
)

/** A side-by-side row: either side may be empty where the other side has an unpaired line. */
data class SplitRow(val left: DiffRow?, val right: DiffRow?)

object DiffLayout {

    /** The hunk's lines numbered and emphasised, in unified order. */
    fun rows(hunk: DiffHunk): List<DiffRow> {
        var old = if (hunk.oldCount == 0) hunk.oldStart + 1 else hunk.oldStart
        var new = if (hunk.newCount == 0) hunk.newStart + 1 else hunk.newStart
        val rows = hunk.lines.map { line ->
            when (line.kind) {
                DiffLineKind.CONTEXT -> DiffRow(line.kind, line.text, old++, new++)
                DiffLineKind.REMOVED -> DiffRow(line.kind, line.text, old++, null)
                DiffLineKind.ADDED -> DiffRow(line.kind, line.text, null, new++)
            }
        }
        return withEmphasis(rows)
    }

    /**
     * Pairs each run of removed lines with the added run that follows it,
     * one-to-one; extra lines on either side stay unpaired. Context lines
     * appear on both sides.
     */
    fun split(rows: List<DiffRow>): List<SplitRow> {
        val out = ArrayList<SplitRow>(rows.size)
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row.kind == DiffLineKind.CONTEXT) {
                out += SplitRow(row, row)
                i++
                continue
            }
            val removed = rows.drop(i).takeWhile { it.kind == DiffLineKind.REMOVED }
            val added = rows.drop(i + removed.size).takeWhile { it.kind == DiffLineKind.ADDED }
            for (k in 0 until maxOf(removed.size, added.size)) {
                out += SplitRow(removed.getOrNull(k), added.getOrNull(k))
            }
            i += removed.size + added.size
        }
        return out
    }

    private fun withEmphasis(rows: List<DiffRow>): List<DiffRow> {
        val out = rows.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i].kind != DiffLineKind.REMOVED) {
                i++
                continue
            }
            val removedEnd = (i until out.size).firstOrNull { out[it].kind != DiffLineKind.REMOVED } ?: out.size
            val addedEnd = (removedEnd until out.size).firstOrNull { out[it].kind != DiffLineKind.ADDED } ?: out.size
            for (k in 0 until minOf(removedEnd - i, addedEnd - removedEnd)) {
                val (a, b) = IntraLineDiff.changedSpans(out[i + k].text, out[removedEnd + k].text)
                out[i + k] = out[i + k].copy(emphasis = a)
                out[removedEnd + k] = out[removedEnd + k].copy(emphasis = b)
            }
            i = addedEnd
        }
        return out
    }
}

/**
 * The differing middle of two lines: everything between their common prefix and
 * common suffix, widened to word boundaries so `fooBar` -> `fooBaz` highlights
 * the whole identifier rather than one letter. O(line length), which is why it
 * is used for every paired line instead of a real diff.
 */
object IntraLineDiff {

    fun changedSpans(old: String, new: String): Pair<IntRange?, IntRange?> {
        if (old == new) return null to null
        var prefix = 0
        val limit = minOf(old.length, new.length)
        while (prefix < limit && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val start = wordStart(old, prefix).coerceAtMost(wordStart(new, prefix))
        return span(old, start, suffix) to span(new, start, suffix)
    }

    private fun span(text: String, start: Int, suffix: Int): IntRange? {
        val end = wordEnd(text, text.length - suffix)
        return if (end > start) start until end else null
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun wordStart(text: String, from: Int): Int {
        var i = from
        while (i > 0 && i <= text.length && isWord(text[i - 1]) && (i == text.length || isWord(text[i]))) i--
        return i
    }

    private fun wordEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && i > 0 && isWord(text[i]) && isWord(text[i - 1])) i++
        return i
    }
}
