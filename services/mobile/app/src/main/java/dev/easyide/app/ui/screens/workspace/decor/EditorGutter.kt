package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import dev.easyide.app.data.settings.LineHighlight
import dev.easyide.app.data.settings.LineNumbers
import dev.easyide.app.ui.theme.EditorColors

/**
 * Start offsets of the logical (newline-separated) lines of a buffer. Only word wrap needs it:
 * without wrap a visual line is a logical line. Built once per buffer, then answers "which
 * logical line is this offset on" and "does a visual line begin a logical line" by binary search.
 */
internal class LineStarts(text: CharSequence) {
    private val starts: IntArray = buildList {
        add(0)
        for (i in text.indices) if (text[i] == '\n') add(i + 1)
    }.toIntArray()

    val count: Int get() = starts.size

    /** The 0-based logical line containing [offset]. */
    fun lineOf(offset: Int): Int {
        val at = starts.binarySearch(offset)
        return if (at >= 0) at else -at - 2
    }

    /** The logical line that begins exactly at [offset], or -1 for a wrapped continuation. */
    fun startingAt(offset: Int): Int = starts.binarySearch(offset).coerceAtLeast(-1)

    fun startOf(line: Int): Int = starts[line]
}

/** Geometry of the gutter: the glyph margin plus room for the line numbers. */
internal object EditorGutter {

    fun isCompact(viewportWidth: Dp): Boolean = viewportWidth < DecorationMetrics.compactBelow

    fun laneWidth(compact: Boolean): Dp =
        if (compact) DecorationMetrics.gutterLaneWidthCompact else DecorationMetrics.gutterLaneWidth

    /** Digits reserved for [totalLines] numbers: enough for the count, never fewer than the minimum. */
    fun digits(totalLines: Int, compact: Boolean): Int {
        val minimum = if (compact) DecorationMetrics.MIN_LINE_NUMBER_DIGITS_COMPACT else DecorationMetrics.MIN_LINE_NUMBER_DIGITS
        return maxOf(minimum, totalLines.toString().length)
    }

    /** The number shown on [line] (0-based) with the caret on [caretLine]; the caret line shows its own number. */
    fun label(mode: LineNumbers, line: Int, caretLine: Int): String = when {
        mode == LineNumbers.RELATIVE && line != caretLine -> kotlin.math.abs(line - caretLine).toString()
        else -> (line + 1).toString()
    }

    /**
     * Full gutter width for a buffer of [totalLines]: glyph margin, the numbers, and one character
     * of gap before the text. With numbers off the margin and the gap remain. The gap lives here,
     * not as padding on the text, so a horizontally scrolled line clips at the gutter edge like
     * VS Code and a focus scroll never eats it.
     */
    fun width(totalLines: Int, mode: LineNumbers, charWidth: Dp, compact: Boolean): Dp {
        val numbers = if (mode == LineNumbers.OFF) 0 else digits(totalLines, compact)
        return laneWidth(compact) + charWidth * numbers + charWidth
    }
}

/** What the gutter paints numbers and the caret line band from; lambdas so a caret move redraws only. */
internal class GutterPaint(
    val layout: () -> TextLayoutResult?,
    /** Pixel range of the text layout on screen, so only those numbers are drawn. */
    val visiblePx: () -> ClosedFloatingPointRange<Float>,
    val selection: () -> TextRange,
    val lineStarts: LineStarts?,
    val mode: LineNumbers,
    val highlight: LineHighlight,
    val colors: EditorColors,
    val numberStyle: TextStyle,
    /** Space kept free at the gutter's right edge: the gap between the numbers and the text. */
    val rightInset: Dp,
    val measurer: TextMeasurer,
)

/**
 * Line numbers right-aligned in the gutter, drawn from the text layout so they stay on their line
 * whatever the wrapping, the line height or the font scale. The caret's number is brighter (VS Code's
 * `editorLineNumber.activeForeground`); [GutterPaint.highlight] tints its band when it asks for the gutter.
 */
internal fun Modifier.gutterLineNumbers(paint: GutterPaint, textTop: Dp): Modifier = drawBehind {
    val result = paint.layout() ?: return@drawBehind
    if (result.lineCount == 0) return@drawBehind
    val selection = paint.selection()
    val caret = selection.start.coerceIn(0, result.layoutInput.text.length)
    val caretLine = paint.lineStarts?.lineOf(caret) ?: result.getLineForOffset(caret)
    val top = textTop.toPx()
    val range = paint.visiblePx()
    val first = result.getLineForVerticalPosition(range.start.coerceAtLeast(0f))
    val last = result.getLineForVerticalPosition(range.endInclusive.coerceAtLeast(0f))
    val band = paint.highlight == LineHighlight.GUTTER || paint.highlight == LineHighlight.ALL
    for (visual in first..last) {
        val lineStart = result.getLineStart(visual)
        val rowLogical = paint.lineStarts?.lineOf(lineStart) ?: visual
        val logical = paint.lineStarts?.startingAt(lineStart) ?: visual
        val lineTop = top + result.getLineTop(visual)
        val height = result.getLineBottom(visual) - result.getLineTop(visual)
        if (band && rowLogical == caretLine && selection.collapsed) drawRect(paint.colors.currentLine, Offset(0f, lineTop), Size(size.width, height))
        if (paint.mode == LineNumbers.OFF || logical < 0) continue
        val color: Color = if (logical == caretLine) paint.colors.gutterActiveText else paint.colors.gutterText
        val measured = paint.measurer.measure(EditorGutter.label(paint.mode, logical, caretLine), paint.numberStyle.copy(color = color))
        drawText(measured, topLeft = Offset(size.width - paint.rightInset.toPx() - measured.size.width, lineTop))
    }
}

/**
 * Sizes the gutter to the text beside it: the layout's height plus [padding] above and below,
 * never less than [minHeight]. Read during measure, so a new layout re-measures this box alone.
 */
internal fun Modifier.matchTextHeight(textLayout: () -> TextLayoutResult?, padding: Dp, minHeight: Dp): Modifier = layout { measurable, constraints ->
    val wanted = (textLayout()?.size?.height ?: 0) + 2 * padding.roundToPx()
    val height = maxOf(wanted, minHeight.roundToPx())
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
