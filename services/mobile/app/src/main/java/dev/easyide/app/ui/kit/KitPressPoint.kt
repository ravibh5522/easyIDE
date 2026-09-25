package dev.easyide.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset

/**
 * Where the last pointer went down on a node, in window coordinates, so a menu opened by a long
 * press or a right click can appear under the finger or the pointer instead of at a corner.
 * Read [at] when the menu opens; it is not state, since nothing draws from it.
 */
class PressPoint {
    private var origin = Offset.Zero
    private var local = Offset.Zero

    val at: IntOffset get() = (origin + local).let { IntOffset(it.x.toInt(), it.y.toInt()) }

    internal fun moved(to: Offset) { origin = to }
    internal fun pressed(at: Offset) { local = at }
}

@Composable
fun rememberPressPoint(): PressPoint = remember { PressPoint() }

/**
 * Records where the node is pressed in [point] without consuming the press, and reports a secondary
 * press (mouse right button, stylus side button) through [onSecondary]. A consumed secondary press
 * never reaches the node's own click handling, so a right click does not also activate the row; an
 * editor that must keep selection working for every input passes `consume = false`.
 */
fun Modifier.kitPressPoint(point: PressPoint, onSecondary: ((IntOffset) -> Unit)? = null, consume: Boolean = true): Modifier =
    onGloballyPositioned { point.moved(it.positionInWindow()) }
        .pointerInput(point, onSecondary, consume) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    point.pressed(event.changes.first().position)
                    if (onSecondary != null && event.buttons.isSecondaryPressed) {
                        if (consume) event.changes.forEach { it.consume() }
                        onSecondary(point.at)
                    }
                }
            }
        }
