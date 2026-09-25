package dev.easyide.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R

internal enum class StepState { Done, Current, Upcoming }

internal fun stepState(index: Int, current: Int): StepState = when {
    index < current -> StepState.Done
    index == current -> StepState.Current
    else -> StepState.Upcoming
}

/** The mono counter, one-based ("2/4"); [current] is clamped so a stale index never reads "5/4". */
internal fun stepCounter(current: Int, steps: Int): String = "${current.coerceIn(0, maxOf(steps - 1, 0)) + 1}/$steps"

/**
 * Onboarding progress: a row of blocks (done muted, current in the accent, upcoming hollow) and
 * the mono counter. The blocks are decoration; the row reads as one "Step n of m" for a screen reader.
 */
@Composable
fun KitStepper(steps: Int, current: Int, modifier: Modifier = Modifier) {
    val colors = Kit.colors
    val position = current.coerceIn(0, maxOf(steps - 1, 0)) + 1
    val description = stringResource(R.string.kitin_step_of, position, steps)
    Row(
        modifier = modifier.kitTag("stepper").semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        repeat(steps) { i ->
            val state = stepState(i, current)
            Canvas(Modifier.size(Kit.space.xxl, Kit.space.s).clearAndSetSemantics {}) {
                when (state) {
                    StepState.Done -> drawRect(colors.textMuted)
                    StepState.Current -> drawRect(colors.accent)
                    StepState.Upcoming -> {
                        val w = Kit.hairline.toPx()
                        drawRect(colors.accent, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                    }
                }
            }
        }
        BasicText(stepCounter(current, steps), Modifier.padding(start = Kit.space.s), style = Kit.text.monoSmall.copy(color = colors.textMuted))
    }
}
