package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * Paints the caret line's band across the whole text area.
 *
 * Reads the layout and caret inside the draw phase, so moving the caret
 * redraws this band and nothing else: no recomposition, no relayout, and the
 * text (in its own graphics layer) is not redrawn. Place it before the text
 * field's padding so the band spans the padding too; [insetTop] is that
 * padding, since the layout's coordinates start inside it.
 */
internal fun Modifier.drawCurrentLine(
    layout: () -> TextLayoutResult?,
    caret: () -> Int,
    color: Color,
    insetTop: Dp,
): Modifier = drawBehind {
    val result = layout() ?: return@drawBehind
    val line = result.getLineForOffset(caret().coerceIn(0, result.layoutInput.text.length))
    val top = result.getLineTop(line)
    drawRect(color, Offset(0f, insetTop.toPx() + top), Size(size.width, result.getLineBottom(line) - top))
}

/**
 * The gutter's "1\n2\n...\nN" text with [activeLine] (0-based) bold in [activeColor].
 * Only rebuilt when the caret changes line, so it costs one pass over the
 * numbers above the caret per line change, not per keystroke.
 */
internal fun gutterNumbers(numbers: String, activeLine: Int, activeColor: Color): AnnotatedString {
    var start = 0
    repeat(activeLine) {
        val next = numbers.indexOf('\n', start)
        if (next < 0) return AnnotatedString(numbers)
        start = next + 1
    }
    val end = numbers.indexOf('\n', start).let { if (it < 0) numbers.length else it }
    return AnnotatedString(
        numbers,
        spanStyles = listOf(
            AnnotatedString.Range(SpanStyle(color = activeColor, fontWeight = FontWeight.Bold), start, end),
        ),
    )
}
