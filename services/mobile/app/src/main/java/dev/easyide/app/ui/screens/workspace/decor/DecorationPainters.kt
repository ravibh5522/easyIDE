package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.theme.DecorationColors

/**
 * Painting of a [DecorationSet] over an already laid-out text field.
 *
 * Everything here runs in the draw phase and reads its inputs through lambdas, so a new
 * decoration snapshot, a caret move or a scroll into a new window invalidates *drawing only*:
 * the text field is neither recomposed nor re-laid-out. That is the whole point of keeping
 * decorations out of the `AnnotatedString` - a span change re-lays-out the entire buffer
 * (decision 0018). Work per redraw is bounded by [visibleLines], not by document length.
 */
internal class DecorationInputs(
    val decorations: () -> DecorationSet?,
    val layout: () -> TextLayoutResult?,
    /** Lines worth painting: the viewport plus overscan, quantised so scrolling rarely changes it. */
    val visibleLines: () -> IntRange,
)

/**
 * The offsets of [lines] in [result], or null when there is nothing to paint: no layout yet,
 * no decorations, or decorations positioned against a different text than the one laid out
 * (for the one frame between an edit and the next layout).
 */
private fun paintWindow(inputs: DecorationInputs): Triple<DecorationSet, TextLayoutResult, IntRange>? {
    val set = inputs.decorations() ?: return null
    if (set.isEmpty) return null
    val result = inputs.layout() ?: return null
    if (set.text != result.layoutInput.text.text || result.lineCount == 0) return null
    val lines = inputs.visibleLines()
    val first = lines.first.coerceIn(0, result.lineCount - 1)
    val last = lines.last.coerceIn(first, result.lineCount - 1)
    return Triple(set, result, result.getLineStart(first)..result.getLineEnd(last))
}

/** Backgrounds under the text; squiggles and end-of-line inlay hints over it. */
internal fun Modifier.textDecorations(
    inputs: DecorationInputs,
    colors: DecorationColors,
    measurer: TextMeasurer,
    ghostStyle: TextStyle,
): Modifier = drawWithContent {
    val window = paintWindow(inputs)
    if (window == null) {
        drawContent()
        return@drawWithContent
    }
    val (set, result, offsets) = window
    drawBackgrounds(set, result, offsets, colors)
    drawContent()
    drawSquiggles(set, result, offsets, colors)
    drawInlayHints(set, result, offsets, colors, measurer, ghostStyle)
}

/** Background layers in paint order, lowest [DecorationLayer.priority] first. */
private val BACKGROUND_LAYERS: List<DecorationLayer<*>> =
    listOf(DecorationLayer.DocumentHighlights, DecorationLayer.SearchMatches).sortedBy { it.priority }

private fun backgroundOf(item: Decoration, colors: DecorationColors): Color? = when (item) {
    is HighlightDecoration -> when (item.kind) {
        HighlightKind.TEXT -> colors.highlightText
        HighlightKind.READ -> colors.highlightRead
        HighlightKind.WRITE -> colors.highlightWrite
    }
    is SearchMatchDecoration -> if (item.isCurrent) colors.searchMatchCurrent else colors.searchMatch
    else -> null
}

private fun DrawScope.drawBackgrounds(set: DecorationSet, result: TextLayoutResult, offsets: IntRange, colors: DecorationColors) {
    for (layer in BACKGROUND_LAYERS) {
        for (item in set.inRange(layer, offsets.first, offsets.last)) {
            if (item.start == item.end) continue
            val color = backgroundOf(item, colors) ?: continue
            // getPathForRange yields one box per line, so a multi-line match needs no splitting.
            val path = result.getPathForRange(item.start, item.end)
            drawPath(path, color)
            if (item is SearchMatchDecoration && item.isCurrent) {
                drawPath(path, colors.searchMatchCurrentBorder, style = Stroke(DecorationMetrics.outlineStroke.toPx()))
            }
        }
    }
}

private fun severityColor(severity: DiagnosticSeverity, colors: DecorationColors): Color = when (severity) {
    DiagnosticSeverity.ERROR -> colors.diagnosticError
    DiagnosticSeverity.WARNING -> colors.diagnosticWarning
    DiagnosticSeverity.INFORMATION -> colors.diagnosticInformation
    DiagnosticSeverity.HINT -> colors.diagnosticHint
}

/** Where the text of [line] ends, excluding its line feed. */
private fun contentEnd(result: TextLayoutResult, line: Int): Int {
    val start = result.getLineStart(line)
    val end = result.getLineEnd(line)
    return if (end > start && result.layoutInput.text[end - 1] == '\n') end - 1 else end
}

private fun DrawScope.drawSquiggles(set: DecorationSet, result: TextLayoutResult, offsets: IntRange, colors: DecorationColors) {
    val amplitude = DecorationMetrics.squiggleAmplitude.toPx()
    val stroke = DecorationMetrics.squiggleStroke.toPx()
    val minWidth = DecorationMetrics.squiggleMinWidth.toPx()
    val firstVisible = result.getLineForOffset(offsets.first)
    val lastVisible = result.getLineForOffset(offsets.last)

    for (d in set.inRange(DecorationLayer.Diagnostics, offsets.first, offsets.last)) {
        if (!d.showSquiggle) continue
        val color = severityColor(d.severity, colors)
        val startLine = result.getLineForOffset(d.start)
        val y = result.getLineBottom(startLine) - amplitude - stroke
        val x0 = result.getHorizontalPosition(d.start, usePrimaryDirection = true)
        if (d.severity == DiagnosticSeverity.HINT) {
            drawHintDots(x0, y, color)
            continue
        }
        // Clamp to the painted window so a diagnostic spanning the whole file costs
        // a screenful of segments, not one per line of the file.
        val endLine = result.getLineForOffset(d.end)
        for (line in maxOf(startLine, firstVisible)..minOf(endLine, lastVisible)) {
            val segStart = maxOf(d.start, result.getLineStart(line))
            val segEnd = minOf(d.end, contentEnd(result, line))
            if (line != startLine && segEnd <= segStart) continue
            val left = result.getHorizontalPosition(segStart, usePrimaryDirection = true)
            val right = if (segEnd > segStart) result.getHorizontalPosition(segEnd, usePrimaryDirection = true) else left
            val lineY = result.getLineBottom(line) - amplitude - stroke
            drawWave(left, maxOf(right, left + minWidth), lineY, amplitude, stroke, color)
        }
    }
}

private fun DrawScope.drawWave(x0: Float, x1: Float, y: Float, amplitude: Float, stroke: Float, color: Color) {
    val half = DecorationMetrics.squiggleWavelength.toPx() / 2
    val path = Path().apply { moveTo(x0, y + amplitude) }
    var x = x0
    var from = y + amplitude
    var to = y - amplitude
    while (x < x1) {
        val next = minOf(x + half, x1)
        // A partial last step keeps the wave's slope instead of jumping to full height.
        path.lineTo(next, from + (to - from) * (next - x) / half)
        x = next
        val peak = to
        to = from
        from = peak
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

private fun DrawScope.drawHintDots(x0: Float, y: Float, color: Color) {
    val radius = DecorationMetrics.hintDotRadius.toPx()
    val spacing = DecorationMetrics.hintDotSpacing.toPx()
    repeat(DecorationMetrics.HINT_DOT_COUNT) { i ->
        drawCircle(color, radius, Offset(x0 + radius + i * spacing, y))
    }
}

/**
 * Inlay hints as ghost text after each line's last character (decision 0018: inline insertion
 * waits for the virtualised editor). Hints on one line are joined in offset order.
 */
private fun DrawScope.drawInlayHints(
    set: DecorationSet,
    result: TextLayoutResult,
    offsets: IntRange,
    colors: DecorationColors,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val hints = set.inRange(DecorationLayer.InlayHints, offsets.first, offsets.last)
    if (hints.isEmpty()) return
    val labels = LinkedHashMap<Int, StringBuilder>()
    for (hint in hints) {
        val line = result.getLineForOffset(hint.offset)
        val label = labels.getOrPut(line) { StringBuilder() }
        if (label.isNotEmpty()) label.append(DecorationMetrics.GHOST_TEXT_SEPARATOR)
        label.append(hint.label)
    }
    val gap = DecorationMetrics.ghostTextGap.toPx()
    val pad = DecorationMetrics.ghostTextPadding.toPx()
    val corner = CornerRadius(DecorationMetrics.ghostTextCorner.toPx())
    for ((line, label) in labels) {
        val measured = measurer.measure(label.toString(), style)
        val top = result.getLineTop(line)
        val y = top + (result.getLineBottom(line) - top - measured.size.height) / 2
        val x = result.getLineRight(line) + gap
        drawRoundRect(
            color = colors.inlayHintBackground,
            topLeft = Offset(x - pad, y),
            size = Size(measured.size.width + 2 * pad, measured.size.height.toFloat()),
            cornerRadius = corner,
        )
        drawText(measured, color = colors.inlayHintText, topLeft = Offset(x, y))
    }
}

/** The icon for each glyph; the one place gutter icons are chosen. */
private fun GutterGlyph.icon(): ImageVector = when (this) {
    GutterGlyph.ERROR -> Icons.Filled.Error
    GutterGlyph.WARNING -> Icons.Filled.Warning
    GutterGlyph.INFORMATION -> Icons.Filled.Info
    GutterGlyph.LIGHTBULB -> Icons.Filled.Lightbulb
    GutterGlyph.CODE_LENS -> Icons.Filled.MoreHoriz
}

private fun GutterGlyph.tint(colors: DecorationColors): Color = when (this) {
    GutterGlyph.ERROR -> colors.diagnosticError
    GutterGlyph.WARNING -> colors.diagnosticWarning
    GutterGlyph.INFORMATION -> colors.diagnosticInformation
    GutterGlyph.LIGHTBULB -> colors.lightbulb
    GutterGlyph.CODE_LENS -> colors.codeLens
}

/** One vector painter per glyph, created once per composition of the gutter. */
@Composable
internal fun rememberGutterPainters(): Map<GutterGlyph, VectorPainter> =
    GutterGlyph.entries.associateWith { rememberVectorPainter(it.icon()) }

/**
 * Gutter glyphs, vertically centred on their text line and horizontally in the [lane] (glyph margin)
 * at the gutter's left edge. Applied to the full gutter box; [textTop] is the gap between the
 * gutter's top and the text layout's first line.
 */
internal fun Modifier.gutterDecorations(
    inputs: DecorationInputs,
    colors: DecorationColors,
    painters: Map<GutterGlyph, VectorPainter>,
    textTop: Dp,
    lane: Dp,
): Modifier = drawBehind {
    val (set, result, offsets) = paintWindow(inputs) ?: return@drawBehind
    val glyphs = GutterGlyphs.byLine(set, offsets.first, offsets.last, result::getLineForOffset)
    if (glyphs.isEmpty()) return@drawBehind
    val icon = DecorationMetrics.gutterIconSize.toPx()
    val left = (lane.toPx() - icon) / 2
    val top = textTop.toPx()
    for ((line, glyph) in glyphs) {
        val painter = painters.getValue(glyph)
        val centre = top + (result.getLineTop(line) + result.getLineBottom(line)) / 2
        translate(left, centre - icon / 2) {
            with(painter) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(glyph.tint(colors))) }
        }
    }
}

/**
 * Reports taps on gutter lines as a 0-based line index, for the lightbulb and code lens
 * glyphs. Taps below the last line are ignored rather than clamped onto it.
 */
internal fun Modifier.gutterTaps(
    layout: () -> TextLayoutResult?,
    textTop: Dp,
    onTap: ((line: Int) -> Unit)?,
): Modifier = if (onTap == null) this else pointerInput(onTap) {
    detectTapGestures { position ->
        val result = layout() ?: return@detectTapGestures
        if (result.lineCount == 0) return@detectTapGestures
        val y = position.y - textTop.toPx()
        if (y < 0 || y > result.getLineBottom(result.lineCount - 1)) return@detectTapGestures
        onTap(result.getLineForVerticalPosition(y))
    }
}
