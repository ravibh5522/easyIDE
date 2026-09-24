package dev.easyide.app.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.props.Motion

/** The three functional states of the block (identity.md 2.1). */
enum class CursorStyle { Solid, Hollow, Blinking }

private const val CURSOR_ASPECT = 0.6f

/** One blink period is on for its first half and off for its second: a hard step with no fade. */
internal fun blinkOn(phase: Float): Boolean = phase < 0.5f

/**
 * The block cursor, drawn (Geist Mono has no U+25AE) so it scales with density and font scale.
 * [CursorStyle.Blinking] runs the app's only infinite transition and falls back to solid when
 * motion is reduced, the property is off or a screen reader is on. Decorative: no semantics.
 */
@Composable
fun CursorBlock(
    modifier: Modifier = Modifier,
    style: CursorStyle = CursorStyle.Solid,
    color: Color = Kit.colors.cursor,
    height: Dp = Kit.space.l,
) {
    val hairline = Kit.hairline
    val phase: State<Float>? = if (style == CursorStyle.Blinking && Kit.motion.cursorBlinks) {
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2 * Motion.BLINK_HALF_MS, easing = LinearEasing)),
            label = "cursor-phase",
        )
    } else null
    // The phase is read while drawing, so a blink redraws the block without recomposing it.
    Box(modifier.size(width = height * CURSOR_ASPECT, height = height).drawBehind {
        if (phase != null && !blinkOn(phase.value)) return@drawBehind
        if (style == CursorStyle.Hollow) {
            val w = hairline.toPx()
            drawRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        } else {
            drawRect(color)
        }
    })
}
