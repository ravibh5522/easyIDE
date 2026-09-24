package dev.easyide.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

private val TRACK = 4.dp
private val THUMB = 20.dp

/** The value a touch at [x] of a [width]-wide track means, clamped to [range] and snapped to [step] (0 = continuous). */
internal fun sliderValueAt(x: Float, width: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (width <= 0f) return range.start
    val raw = range.start + (range.endInclusive - range.start) * (x / width).coerceIn(0f, 1f)
    if (step <= 0f) return raw
    val snapped = range.start + Math.round((raw - range.start) / step) * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

/**
 * A drawn slider: a hairline-weight track filled to the value in the accent, a square thumb. The hit box is the touch
 * floor tall; a screen reader gets the range and sets values directly.
 */
@Composable
fun KitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    enabled: Boolean = true,
) {
    val colors = Kit.colors
    val current by rememberUpdatedState(value.coerceIn(range.start, range.endInclusive))
    val emit by rememberUpdatedState(onValueChange)
    val fraction = if (range.endInclusive > range.start) (current - range.start) / (range.endInclusive - range.start) else 0f
    Canvas(
        modifier
            .kitTag("slider")
            .fillMaxWidth()
            .height(Kit.metrics.touchFloor)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current, range)
                if (!enabled) disabled()
                setProgress { target -> emit(target.coerceIn(range.start, range.endInclusive)); true }
            }
            .pointerInput(enabled, range, step) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> emit(sliderValueAt(p.x, size.width.toFloat(), range, step)) }
            }
            .pointerInput(enabled, range, step) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { moved, _ -> moved.consume(); emit(sliderValueAt(moved.position.x, size.width.toFloat(), range, step)) }
            },
    ) {
        val track = TRACK.toPx()
        val thumb = THUMB.toPx()
        val y = (size.height - track) / 2
        val usable = size.width - thumb
        val x = thumb / 2 + usable * fraction
        drawRoundRect(colors.panelBorder, Offset(thumb / 2, y), Size(usable, track), CornerRadius(track / 2))
        drawRoundRect(if (enabled) colors.accent else colors.textDisabled, Offset(thumb / 2, y), Size(usable * fraction, track), CornerRadius(track / 2))
        drawRect(if (enabled) colors.accent else colors.textDisabled, Offset(x - thumb / 2, (size.height - thumb) / 2), Size(thumb, thumb))
    }
}
