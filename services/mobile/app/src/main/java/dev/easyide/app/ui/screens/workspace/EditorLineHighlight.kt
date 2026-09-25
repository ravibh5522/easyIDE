package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import dev.easyide.app.data.settings.LineHighlight
import dev.easyide.app.ui.screens.workspace.decor.LineStarts

/** The box of `[left, top, left + width, top + height)` outlined inside its bounds, so the stroke never spills over neighbours. */
private fun DrawScope.outline(color: Color, topLeft: Offset, size: Size, stroke: Float) {
    val half = stroke / 2
    drawRect(color, Offset(topLeft.x + half, topLeft.y + half), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
}

/**
 * Paints the caret line's band across the whole text area (`editor.renderLineHighlight`),
 * with a border when the mode is `all`. A wrapped line is highlighted as one block, and nothing
 * is painted while text is selected, as in VS Code.
 *
 * Reads the layout and caret inside the draw phase, so moving the caret
 * redraws this band and nothing else: no recomposition, no relayout, and the
 * text (in its own graphics layer) is not redrawn. Place it before the text
 * field's padding so the band spans the padding too; [insetTop] is that
 * padding, since the layout's coordinates start inside it.
 */
internal fun Modifier.drawCurrentLine(
    layout: () -> TextLayoutResult?,
    selection: () -> TextRange,
    lineStarts: LineStarts?,
    mode: LineHighlight,
    fill: Color,
    border: Color,
    stroke: Dp,
    insetTop: Dp,
): Modifier = if (!mode.paintsLine) this else drawBehind {
    val result = layout() ?: return@drawBehind
    val sel = selection()
    if (!sel.collapsed) return@drawBehind
    val length = result.layoutInput.text.length
    val caret = sel.start.coerceIn(0, length)
    var first = result.getLineForOffset(caret)
    var last = first
    if (lineStarts != null) {
        val logical = lineStarts.lineOf(caret)
        first = result.getLineForOffset(lineStarts.startOf(logical))
        last = result.getLineForOffset(if (logical + 1 < lineStarts.count) lineStarts.startOf(logical + 1) - 1 else length)
    }
    val top = insetTop.toPx() + result.getLineTop(first)
    val box = Size(size.width, result.getLineBottom(last) - result.getLineTop(first))
    drawRect(fill, Offset(0f, top), box)
    if (mode.paintsBorder) outline(border, Offset(0f, top), box, stroke.toPx())
}

/**
 * Outlines the bracket pair at the caret: a faint fill and a 1dp square border, like VS Code's
 * `editorBracketMatch`. Drawn from the existing text layout rather than as spans in the visual
 * transformation: a span change would make the field re-lay-out the whole buffer on every caret move.
 */
internal fun Modifier.drawBracketMatch(
    pair: Pair<Int, Int>?,
    layout: () -> TextLayoutResult?,
    fill: Color,
    border: Color,
    stroke: Dp,
): Modifier = if (pair == null) this else drawBehind {
    val result = layout() ?: return@drawBehind
    val length = result.layoutInput.text.length
    for (offset in intArrayOf(pair.first, pair.second)) {
        if (offset >= length) continue
        val box = result.getBoundingBox(offset)
        drawRect(fill, box.topLeft, box.size)
        outline(border, box.topLeft, box.size, stroke.toPx())
    }
}
