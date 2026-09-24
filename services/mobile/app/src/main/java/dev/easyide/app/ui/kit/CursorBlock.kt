package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.props.BlinkClock
import kotlinx.coroutines.delay

/** The three functional states of the block (identity.md 2.1). */
enum class CursorStyle { Solid, Hollow, Blinking }

private const val CURSOR_ASPECT = 0.6f
private const val NANOS_PER_MS = 1_000_000L

/** Monotonic milliseconds: the clock [CursorBlock] reads, and the one a caller stamps input with. */
fun cursorClockMs(): Long = System.nanoTime() / NANOS_PER_MS

/**
 * The block cursor, drawn (Geist Mono has no U+25AE) so it scales with density and font scale.
 * [CursorStyle.Blinking] follows [BlinkClock]: it holds solid for 600ms after [lastInputMs] (a
 * [cursorClockMs] reading), and rests solid after [maxCycles] cycles when a cap is given. It falls
 * back to solid when motion is reduced, the property is off or a screen reader is on. The wake-up
 * loop is the app's only looping animation and ends when the cursor is solid for good.
 * Decorative: no semantics.
 */
@Composable
fun CursorBlock(
    modifier: Modifier = Modifier,
    style: CursorStyle = CursorStyle.Solid,
    color: Color = Kit.colors.cursor,
    height: Dp = Kit.space.l,
    maxCycles: Int? = null,
    lastInputMs: Long? = null,
) {
    val hairline = Kit.hairline
    val blinking = style == CursorStyle.Blinking && Kit.motion.cursorBlinks
    val shown = remember { mutableStateOf(true) }
    val startMs = remember(blinking, maxCycles) { cursorClockMs() }
    LaunchedEffect(blinking, maxCycles, lastInputMs) {
        if (!blinking) {
            shown.value = true
            return@LaunchedEffect
        }
        while (true) {
            val now = cursorClockMs()
            shown.value = BlinkClock.visible(now, startMs, lastInputMs, maxCycles)
            delay(BlinkClock.msUntilChange(now, startMs, lastInputMs, maxCycles) ?: break)
        }
        shown.value = true
    }
    // The state is read while drawing, so a blink redraws the block without recomposing it.
    Box(modifier.size(width = height * CURSOR_ASPECT, height = height).drawBehind {
        if (!shown.value) return@drawBehind
        if (style == CursorStyle.Hollow) {
            val w = hairline.toPx()
            drawRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        } else {
            drawRect(color)
        }
    })
}
