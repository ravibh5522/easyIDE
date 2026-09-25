package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

private const val WIDTH_RATIO = 0.5f
private const val STROKE_RATIO = 0.16f

/** A right-angle tip's miter reaches this many stroke widths past its vertex; the tip is pulled in by it. */
private const val MITER_REACH = 0.71f

/**
 * The prompt glyph ">" that leads section headers and marks the active list row (identity.md
 * 2.2). Drawn as a 45-degree chevron with butt ends and a mitre join, not typed: the bundled
 * fonts differ in whether and how they carry it. Decorative: no semantics.
 */
@Composable
fun PromptGlyph(
    modifier: Modifier = Modifier,
    color: Color = Kit.colors.textMuted,
    height: Dp = Kit.space.m,
) {
    Box(modifier.size(width = height * WIDTH_RATIO, height = height).drawBehind {
        val stroke = size.height * STROKE_RATIO
        val tipX = size.width - stroke * MITER_REACH
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(tipX, size.height / 2)
            lineTo(0f, size.height)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
    })
}
