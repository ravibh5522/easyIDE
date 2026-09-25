package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import dev.easyide.app.data.settings.CursorStyle
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.decor.DecorationMetrics
import kotlinx.coroutines.delay

/**
 * Whether the caret is in the "on" half of its blink. The phase restarts as visible whenever
 * [restartKey] changes (a keystroke or a caret move), so the caret is solid while typing, then
 * blinks every [DecorationMetrics.CARET_BLINK_MS] once idle. Solid when blinking is off in
 * appearance settings or motion is reduced, and while [active] is false there is no timer at all.
 */
@Composable
internal fun rememberCaretPhase(restartKey: Any?, active: Boolean): State<Boolean> {
    val blinks = Kit.motion.cursorBlinks
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, active, blinks) {
        on.value = true
        if (!active || !blinks) return@LaunchedEffect
        while (true) {
            delay(DecorationMetrics.CARET_BLINK_MS)
            on.value = !on.value
        }
    }
    return on
}

/**
 * Draws the caret (`editor.cursorStyle`): a [width] line, a translucent block over the character,
 * or an underline. The field's own caret is hidden (transparent brush) because it can only be a
 * 2dp line with no style. Reads its state in the draw phase, so a blink redraws this layer only.
 * Place it inside the field's padding so the layout's coordinates apply directly.
 */
internal fun Modifier.drawCaret(
    layout: () -> TextLayoutResult?,
    selection: () -> TextRange,
    visible: State<Boolean>,
    focused: State<Boolean>,
    style: CursorStyle,
    width: Dp,
    color: Color,
    fallbackCharWidth: Float,
): Modifier = drawWithContent {
    drawContent()
    val sel = selection()
    val result = layout()
    if (result == null || !sel.collapsed || !focused.value || !visible.value) return@drawWithContent
    val text = result.layoutInput.text
    val offset = sel.start.coerceIn(0, text.length)
    val rect = result.getCursorRect(offset)
    val onChar = offset < text.length && text[offset] != '\n'
    val cell = if (onChar) result.getBoundingBox(offset).width else fallbackCharWidth
    when (style) {
        CursorStyle.LINE -> drawRect(color, Offset(rect.left, rect.top), Size(width.toPx(), rect.height))
        CursorStyle.BLOCK -> drawRect(color.copy(alpha = DecorationMetrics.BLOCK_CARET_ALPHA), Offset(rect.left, rect.top), Size(cell, rect.height))
        CursorStyle.UNDERLINE -> {
            val thickness = DecorationMetrics.underlineCaretHeight.toPx()
            drawRect(color, Offset(rect.left, rect.bottom - thickness), Size(cell, thickness))
        }
    }
}
