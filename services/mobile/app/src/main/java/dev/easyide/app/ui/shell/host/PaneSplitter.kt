package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.screens.workspace.layout.LayoutTokens
import dev.easyide.app.ui.screens.workspace.layout.PaneLimits
import dev.easyide.app.ui.screens.workspace.layout.SplitterMath

/**
 * The draggable edge between a docked panel and the stage. The finger accumulates an unsnapped
 * running value and [SplitterMath] turns it into the size shown, so snapping never eats small
 * moves. Double tap resets to the default; with focus, the arrow keys move it by a step. The
 * drawn line is a hairline; the grab area around it is [LayoutTokens.splitterGrab].
 *
 * A [vertical] splitter sits on the top edge of a bottom panel (its drag is up and down); the
 * default one sits on a side edge. [growsTowardsStart] is for a panel on the far side of its edge
 * (right of the stage, below it): dragging towards the stage makes it larger.
 */
@Composable
fun PaneSplitter(
    sizeDp: Float,
    limits: PaneLimits,
    ceilingDp: Float,
    onSize: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    growsTowardsStart: Boolean = false,
) {
    val density = LocalDensity.current.density
    var raw by remember { mutableFloatStateOf(sizeDp) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val label = stringResource(R.string.shell_panel_resize)
    val value = stringResource(if (vertical) R.string.wshell_bottom_height_value else R.string.shell_panel_resize_value, sizeDp.toInt())
    val colors = Kit.colors
    val sign = if (growsTowardsStart) -1 else 1
    val (towardsStart, towardsEnd) = if (vertical) Key.DirectionUp to Key.DirectionDown else Key.DirectionLeft to Key.DirectionRight
    val frame = if (vertical) Modifier.fillMaxWidth().height(LayoutTokens.splitterGrab) else Modifier.fillMaxHeight().width(LayoutTokens.splitterGrab)
    val line = if (vertical) Modifier.fillMaxWidth().height(if (focused) Kit.marker else ShellTokens.splitterLine) else Modifier.fillMaxHeight().width(if (focused) Kit.marker else ShellTokens.splitterLine)
    Box(
        modifier
            .then(frame)
            .kitTag("pane-splitter")
            .semantics { contentDescription = label; stateDescription = value }
            .draggable(
                orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    raw += sign * delta / density
                    onSize(SplitterMath.resolve(raw, limits, ceilingDp).size)
                },
                onDragStarted = { raw = sizeDp },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    towardsStart -> -sign
                    towardsEnd -> sign
                    else -> 0
                }
                if (direction == 0 || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                onSize(SplitterMath.step(sizeDp, direction, limits, ceilingDp).size)
                true
            }
            .focusable(interactionSource = source),
        contentAlignment = Alignment.Center,
    ) {
        Box(line.background(if (focused) colors.focus else colors.panelBorder))
    }
}
