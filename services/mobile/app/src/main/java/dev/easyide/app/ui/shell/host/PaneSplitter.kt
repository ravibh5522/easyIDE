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
 */
@Composable
fun PaneSplitter(
    sizeDp: Float,
    limits: PaneLimits,
    ceilingDp: Float,
    onSize: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var raw by remember { mutableFloatStateOf(sizeDp) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val label = stringResource(R.string.shell_panel_resize)
    val value = stringResource(R.string.shell_panel_resize_value, sizeDp.toInt())
    val colors = Kit.colors
    Box(
        modifier
            .fillMaxHeight()
            .width(LayoutTokens.splitterGrab)
            .kitTag("pane-splitter")
            .semantics { contentDescription = label; stateDescription = value }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    raw += delta / density
                    onSize(SplitterMath.resolve(raw, limits, ceilingDp).size)
                },
                onDragStarted = { raw = sizeDp },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                if (direction == 0 || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                onSize(SplitterMath.step(sizeDp, direction, limits, ceilingDp).size)
                true
            }
            .focusable(interactionSource = source),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxHeight().width(if (focused) Kit.marker else ShellTokens.splitterLine)
                .background(if (focused) colors.focus else colors.panelBorder),
        )
    }
}
