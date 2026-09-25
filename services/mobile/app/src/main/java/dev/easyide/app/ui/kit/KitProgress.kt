package dev.easyide.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import kotlinx.coroutines.delay

/** The loading cursor's timing (identity.md 10): eight cells, 90ms per step, nothing for the first 300ms. */
internal object Sweep {
    const val CELLS = 8
    const val STEP_MS = 90L
    const val DELAY_MS = 300L
    const val HIDDEN = -1

    /** The lit cell [elapsedMs] after loading began, or [HIDDEN] during the delay so a fast load never flashes. */
    fun cellAt(elapsedMs: Long): Int =
        if (elapsedMs < DELAY_MS) HIDDEN else (((elapsedMs - DELAY_MS) / STEP_MS) % CELLS).toInt()
}

/**
 * Progress: a [CellFillBar] for a known [fraction], otherwise a cursor sweeping eight cells.
 * With reduced motion the sweep does not move; after the delay it rests on the first cell.
 */
@Composable
fun KitProgress(fraction: Float?, modifier: Modifier = Modifier, tone: Tone = Tone.Accent) {
    if (fraction != null) {
        CellFillBar(fraction, modifier, tone)
        return
    }
    val reduce = Kit.motion.reduce
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduce) {
        elapsed = 0L
        if (reduce) {
            delay(Sweep.DELAY_MS)
            elapsed = Sweep.DELAY_MS
            return@LaunchedEffect
        }
        while (true) {
            delay(Sweep.STEP_MS)
            elapsed += Sweep.STEP_MS
        }
    }
    val loading = stringResource(R.string.kit_loading)
    CellTrack(
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            contentDescription = loading
        },
        on = tone.content(Kit.colors),
        fixedCells = Sweep.CELLS,
        cursor = Sweep.cellAt(elapsed),
    )
}
