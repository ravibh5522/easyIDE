package dev.easyide.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap target that also opens a context menu the two ways a tablet offers one:
 * long-press for touch and secondary-button press for a mouse or stylus.
 *
 * The secondary press is observed without being consumed, so it never blocks
 * the ordinary click and hover handling underneath.
 *
 * @param onLongClickLabel what TalkBack announces for the long-press action.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.clickableWithContextMenu(
    onClick: () -> Unit,
    onContextMenu: () -> Unit,
    onLongClickLabel: String?,
): Modifier = this
    .combinedClickable(onClick = onClick, onLongClick = onContextMenu, onLongClickLabel = onLongClickLabel)
    .pointerInput(onContextMenu) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) onContextMenu()
            }
        }
    }
