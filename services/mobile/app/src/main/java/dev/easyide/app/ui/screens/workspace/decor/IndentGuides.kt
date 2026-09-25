package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp

/** How many indent levels (guides) a line has; pure so the blank-line rule is testable. */
internal object IndentGuides {

    /** Leading columns of `[start, end)`: a space is one, a tab is [tabSize]; -1 for a blank line. */
    fun columns(text: CharSequence, start: Int, end: Int, tabSize: Int): Int {
        var cols = 0
        for (i in start until end) {
            when (text[i]) {
                ' ' -> cols++
                '\t' -> cols += tabSize
                '\n', '\r' -> return -1
                else -> return cols
            }
        }
        return -1
    }

    /**
     * Guide count per line of [columns] (`-1` = blank). A blank line continues the guides its
     * neighbours share: the smaller of the previous and next non-blank indents, so a guide runs
     * unbroken through an empty line in a block.
     */
    fun levels(columns: IntArray, tabSize: Int): IntArray {
        val out = IntArray(columns.size)
        var next = -1
        val following = IntArray(columns.size)
        for (i in columns.indices.reversed()) {
            if (columns[i] >= 0) next = columns[i]
            following[i] = next
        }
        var prev = 0
        for (i in columns.indices) {
            if (columns[i] >= 0) {
                prev = columns[i]
                out[i] = columns[i] / tabSize
            } else {
                out[i] = minOf(prev, if (following[i] < 0) 0 else following[i]) / tabSize
            }
        }
        return out
    }
}

/** What the guides read: the same lambdas the other painters use, plus the scroll position. */
internal class GuidePaint(
    val layout: () -> TextLayoutResult?,
    /** Pixel range of the text layout currently on screen. */
    val visiblePx: () -> ClosedFloatingPointRange<Float>,
    val tabSize: Int,
    val charWidth: Float,
    val color: Color,
    val stroke: Dp,
)

/**
 * Vertical indent guides behind the text: one 1dp line per indent level, through blank lines and
 * across wrapped continuations. Only the lines on screen are examined, so a long file costs a screenful.
 */
internal fun Modifier.indentGuides(paint: GuidePaint, enabled: Boolean): Modifier = if (!enabled) this else drawBehind {
    val result = paint.layout() ?: return@drawBehind
    if (result.lineCount == 0) return@drawBehind
    val text = result.layoutInput.text
    val range = paint.visiblePx()
    // A few lines of margin above so a blank line at the top still finds the indent it continues.
    val first = (result.getLineForVerticalPosition(range.start) - LOOKAROUND_LINES).coerceAtLeast(0)
    val last = (result.getLineForVerticalPosition(range.endInclusive) + LOOKAROUND_LINES).coerceAtMost(result.lineCount - 1)
    val cols = IntArray(last - first + 1) { i ->
        val line = first + i
        val start = result.getLineStart(line)
        if (start > 0 && text[start - 1] != '\n') CONTINUATION else IndentGuides.columns(text, start, result.getLineEnd(line), paint.tabSize)
    }
    // A wrapped continuation repeats the guides of the line it continues.
    var carry = 0
    val levels = IndentGuides.levels(IntArray(cols.size) { if (cols[it] == CONTINUATION) -1 else cols[it] }, paint.tabSize)
    val stroke = paint.stroke.toPx()
    for (i in cols.indices) {
        val line = first + i
        if (cols[i] == CONTINUATION) levels[i] = carry else carry = levels[i]
        if (levels[i] == 0) continue
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        if (bottom < range.start || top > range.endInclusive) continue
        val start = result.getLineStart(line)
        for (level in 0 until levels[i]) {
            val column = level * paint.tabSize
            // Tab-indented lines hold one character per level, space-indented ones one per column.
            val at = start + if (cols[i] >= 0 && text[start] == '\t') level else column
            val x = if (cols[i] >= 0 && at < result.getLineEnd(line)) result.getHorizontalPosition(at, true) else column * paint.charWidth
            drawLine(paint.color, Offset(x + stroke / 2, top), Offset(x + stroke / 2, bottom), stroke)
        }
    }
}

private const val LOOKAROUND_LINES = 40
private const val CONTINUATION = -2
