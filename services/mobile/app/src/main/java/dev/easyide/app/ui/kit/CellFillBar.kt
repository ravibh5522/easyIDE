package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import kotlin.math.floor
import kotlin.math.roundToInt

/** How many whole cells of [cellPx] with [gapPx] between them fit in [widthPx]. */
internal fun cellCount(widthPx: Float, cellPx: Float, gapPx: Float): Int =
    if (cellPx <= 0f) 0 else floor((widthPx + gapPx) / (cellPx + gapPx)).toInt().coerceAtLeast(0)

/** Cells filled at [fraction]; the epsilon keeps 0.3 of 10 from landing on 2 through float error. */
internal fun filledCells(fraction: Float, cells: Int): Int =
    if (fraction.isNaN()) 0 else floor(fraction.coerceIn(0f, 1f) * cells + FILL_EPSILON).toInt().coerceAtMost(cells)

private const val FILL_EPSILON = 1e-4f

/**
 * A row of blocks. With [fixedCells] null it fills the width with as many cells as fit and lights
 * the first `fraction` of them; with a count it is that many cells wide and lights only [cursor]
 * (the loading sweep). Unlit cells are drawn in the hairline tone so the bar's length reads.
 */
@Composable
internal fun CellTrack(
    modifier: Modifier,
    on: Color,
    fixedCells: Int? = null,
    fraction: Float = 0f,
    cursor: Int = -1,
) {
    val off = Kit.colors.panelBorder
    val cell = Kit.space.s
    val gap = Kit.space.xxs
    val sized = if (fixedCells == null) Modifier.fillMaxWidth() else Modifier.width(cell * fixedCells + gap * (fixedCells - 1))
    Box(modifier.then(sized).height(Kit.space.m).drawBehind {
        val cellPx = cell.toPx()
        val gapPx = gap.toPx()
        val count = fixedCells ?: cellCount(size.width, cellPx, gapPx)
        val filled = if (fixedCells == null) filledCells(fraction, count) else 0
        for (i in 0 until count) {
            drawRect(if (i < filled || i == cursor) on else off, Offset(i * (cellPx + gapPx), 0f), Size(cellPx, size.height))
        }
    })
}

/** Determinate progress (identity.md 2.1 "cell fill"): installs, downloads, indexing. */
@Composable
fun CellFillBar(fraction: Float, modifier: Modifier = Modifier, tone: Tone = Tone.Accent) {
    val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val percent = stringResource(R.string.kit_progress_percent, (clamped * 100).roundToInt())
    CellTrack(
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            stateDescription = percent
        },
        on = tone.content(Kit.colors),
        fraction = clamped,
    )
}
