package dev.easyide.lsp.text

import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range

/**
 * Offset <-> [Position] over one immutable text. Kotlin strings are UTF-16 and the client
 * offers only `utf-16`, so a column is a plain `String` index and no transcoding happens.
 *
 * Line terminators are `\n`, `\r\n` and `\r`, as the LSP spec defines them.
 */
class LineIndex(private val text: String) {

    /** Start offset of every line; the text always has at least one line. */
    private val starts: IntArray = buildStarts(text)

    val lineCount: Int get() = starts.size

    /** Position of [offset], clamped into `0..text.length`. */
    fun position(offset: Int): Position {
        val o = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return Position(lo, o - starts[lo])
    }

    /**
     * Offset of [pos]. A line past the end clamps to the end of the text; a column past the
     * line's end clamps to the line end (before its terminator), per the spec's guidance.
     */
    fun offset(pos: Position): Int {
        if (pos.line >= starts.size) return text.length
        val start = starts[pos.line]
        return start + pos.character.coerceAtMost(contentEnd(pos.line) - start)
    }

    fun range(start: Int, end: Int): Range = Range(position(start), position(end))

    /** End offset of [line]'s content, excluding its terminator. */
    private fun contentEnd(line: Int): Int {
        var end = if (line + 1 < starts.size) starts[line + 1] else text.length
        if (end > starts[line] && line + 1 < starts.size) {
            end--
            if (text[end] == '\n' && end > starts[line] && text[end - 1] == '\r') end--
        }
        return end
    }

    private companion object {
        fun buildStarts(text: String): IntArray {
            val out = ArrayList<Int>()
            out += 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> out += i + 1
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        out += i + 1
                    }
                }
                i++
            }
            return out.toIntArray()
        }
    }
}
