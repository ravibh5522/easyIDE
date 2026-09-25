package dev.easyide.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * One glyph of the easyIDE set (identity.md 6), kept as path data so a test can read the
 * geometry and the [vector] is built from exactly that data.
 *
 * The rules are the grid, not taste: a 24 grid with a 20 live area, a 1.5 stroke with butt caps
 * and mitre joins, and only horizontal, vertical or 45-degree segments (the "chamfers instead of
 * round corners" rule). Path data is therefore M, L, H, V and Z only. [outline] is stroked;
 * [fill] is the one filled block of the glyph (the cursor motif), never a second tone.
 *
 * The vector is drawn in opaque black that the tint of the host `Icon`/`Image` replaces, so a
 * glyph never carries a colour of its own.
 */
class EiGlyph(val name: String, val outline: String = "", val fill: String = "") {
    val outlineNodes get() = PathParser().parsePathString(outline).toNodes()
    val fillNodes get() = PathParser().parsePathString(fill).toNodes()

    val vector: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        val ink = SolidColor(Color.Black)
        ImageVector.Builder("ei_$name", GRID.dp, GRID.dp, GRID, GRID).apply {
            if (outline.isNotEmpty()) {
                addPath(
                    outlineNodes, stroke = ink, strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = MITRE_LIMIT,
                )
            }
            if (fill.isNotEmpty()) addPath(fillNodes, fill = ink)
        }.build()
    }

    companion object {
        const val GRID = 24f
        const val LIVE_MIN = 2f
        const val LIVE_MAX = 22f
        const val STROKE = 1.5f

        /** High enough that a 45-degree corner keeps its point instead of being bevelled. */
        private const val MITRE_LIMIT = 4f
    }
}
