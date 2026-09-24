package dev.easyide.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

private val LEG = 6.dp

/**
 * Four 6dp L-marks at the corners of the element: "selected" or "hero" without elevation or a
 * tinted fill. Drawn over the content, inside the bounds. At most one element per view should
 * carry them; nothing draws when `appearance.motif` is off or [enabled] is false.
 */
@Composable
fun Modifier.cropCorners(tone: Tone = Tone.Accent, enabled: Boolean = true): Modifier {
    if (!enabled || !Kit.feel.motif.drawsSupporting) return this
    val color = tone.content(Kit.colors)
    val thickness = Kit.marker
    return drawWithContent {
        drawContent()
        val leg = LEG.toPx()
        val t = thickness.toPx()
        for (right in 0..1) for (bottom in 0..1) {
            val x = if (right == 1) size.width else 0f
            val y = if (bottom == 1) size.height else 0f
            val dx = if (right == 1) -1f else 1f
            val dy = if (bottom == 1) -1f else 1f
            // Each arm is a rectangle anchored on the corner, so the corner pixel is filled exactly.
            drawRect(color, Offset(minOf(x, x + dx * leg), minOf(y, y + dy * t)), Size(leg, t))
            drawRect(color, Offset(minOf(x, x + dx * t), minOf(y, y + dy * leg)), Size(t, leg))
        }
    }
}
