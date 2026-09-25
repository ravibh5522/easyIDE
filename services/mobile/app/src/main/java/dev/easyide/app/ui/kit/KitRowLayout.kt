package dev.easyide.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.props.Density
import kotlin.math.min

/** One line is the default at every density; a second line needs the caller's ask (U-DEN-01). */
internal enum class RowLines { One, Two }

/** Two lines only when there is a description, the caller asked, and the row is not plain dense (or is selected). */
internal fun rowLines(density: Density, hasDescription: Boolean, secondLine: Boolean, selected: Boolean): RowLines =
    if (hasDescription && secondLine && (density != Density.DENSE || selected)) RowLines.Two else RowLines.One

/** The start edge of a row's text: gutter, tree indent, then the fixed columns that are present. */
internal fun rowTextEdge(hPad: Dp, indent: Dp, level: Int, twistie: Boolean, leading: Boolean, gap: Dp): Dp {
    var edge = hPad + indent * level
    if (twistie) edge += KitSizes.twistieSlot + gap
    if (leading) edge += KitSizes.leadingSlot + gap
    return edge
}

/** What is left for the description after the title took its width, or 0 when a stub would be all that fits. */
internal fun descriptionWidth(available: Int, titleWidth: Int, gap: Int, minimum: Int): Int {
    val left = available - titleWidth - gap
    return if (left >= minimum) left else 0
}

/** A slot never makes its row taller than the row token: a 32dp switch in a 28dp row is centred and overflows. */
internal fun slotHeight(content: Int, row: Int): Int = min(content, row)

/**
 * Title first, then the description in the width that is left: the title never gives way to it
 * (U-DEN-01). The description is dropped when less than [descriptionMin] remains.
 */
@Composable
internal fun TitleAndDescription(gap: Dp, modifier: Modifier = Modifier, title: @Composable () -> Unit, description: @Composable () -> Unit) {
    Layout({ title(); description() }, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val t = measurables[0].measure(loose)
        val room = descriptionWidth(constraints.maxWidth, t.width, gapPx, KitSizes.descriptionMin.roundToPx())
        val d = if (room > 0) measurables.getOrNull(1)?.measure(loose.copy(maxWidth = room)) else null
        val width = if (d == null) t.width else t.width + gapPx + d.width
        val height = maxOf(t.height, d?.height ?: 0)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            t.placeRelative(0, (height - t.height) / 2)
            d?.placeRelative(t.width + gapPx, (height - d.height) / 2)
        }
    }
}

/** Centres the content in [row] tall, letting it overflow instead of growing the row. */
internal fun Modifier.kitClampHeight(row: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = slotHeight(p.height, row.roundToPx())
    layout(p.width, h) { p.placeRelative(0, (h - p.height) / 2) }
}
