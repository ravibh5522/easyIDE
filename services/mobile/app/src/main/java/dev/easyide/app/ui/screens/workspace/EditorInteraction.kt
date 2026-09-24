package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.screens.workspace.edit.TextState
import kotlinx.coroutines.flow.StateFlow

/**
 * A caret or selection the editor should adopt - after a completion was accepted, or to reveal
 * a navigation target. Offsets refer to [text]; the editor applies the request only once its
 * buffer is exactly that text, so a request never lands one edit out of place. [id] makes two
 * identical requests (reveal the same line twice) distinct.
 */
data class SelectionRequest(
    val path: String,
    val text: String,
    val start: Int,
    val end: Int,
    val reveal: Boolean,
    val id: Long,
)

/**
 * What the editing surface reports to language features, and the little it accepts back.
 * Engine-neutral like the decoration seams of decision 0018: the next editor engine calls
 * the same methods from its own input handling.
 */
interface EditorInteraction {

    /** A key while the editor has focus, before the text field; true consumes it. */
    fun onPreviewKey(path: String, event: KeyEvent): Boolean

    /**
     * A user edit from [before] to [after] (typing rules already applied). Returning a
     * different state replaces the edit - Enter accepting a completion, a commit character
     * inserting after the accepted item - so the buffer changes once, not twice.
     */
    fun transformEdit(path: String, before: TextState, after: TextState): TextState?

    /** The buffer or caret changed; called after every composition that changed either. */
    fun onCaretChanged(path: String, text: String, selection: TextRange)

    /** Lines 0-based inclusive that are on screen (plus the painter's overscan). */
    fun onVisibleLinesChanged(path: String, first: Int, last: Int)

    /** A touch long-press at [offset]; the text field also selects the word, as it always has. */
    fun onLongPress(path: String, offset: Int)

    /** A mouse pointer resting at [offset], or null once it left the text. */
    fun onPointerHover(path: String, offset: Int?)

    /** Ctrl+click (mouse) or Ctrl+tap (touch with a hardware keyboard) at [offset]. */
    fun onCtrlClick(path: String, offset: Int)

    val selectionRequests: StateFlow<SelectionRequest?>

    fun onSelectionRequestApplied(request: SelectionRequest)
}

/**
 * Long-press, Ctrl+click and mouse hover over the laid-out text, observed in the Initial pass
 * without consuming anything, so the text field's own gestures (caret placement, word
 * selection, the text toolbar) behave exactly as before.
 */
internal fun Modifier.editorPointer(
    path: String,
    interaction: EditorInteraction?,
    layout: () -> TextLayoutResult?,
): Modifier {
    if (interaction == null) return this
    fun offsetAt(position: Offset): Int? = layout()?.getOffsetForPosition(position)
    return pointerInput(path, interaction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentEvent.keyboardModifiers.isCtrlPressed) {
                offsetAt(down.position)?.let { interaction.onCtrlClick(path, it) }
                return@awaitEachGesture
            }
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            // Completes (non-null) when the finger lifts or moves first; null means it held still.
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                do {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                } while (change != null && change.pressed && (change.position - down.position).getDistance() <= slop)
            }
            if (interrupted == null) offsetAt(down.position)?.let { interaction.onLongPress(path, it) }
        }
    }.pointerInput(path, interaction) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Move, PointerEventType.Enter -> interaction.onPointerHover(path, offsetAt(change.position))
                    PointerEventType.Exit -> interaction.onPointerHover(path, null)
                }
            }
        }
    }
}

/**
 * Reports a secondary-button press (mouse right click, stylus side button) with its window position,
 * without consuming it, so selection and the text toolbar keep working for primary input and the
 * context menu opens where the pointer is.
 */
@Composable
internal fun Modifier.secondaryClicks(onSecondaryClick: ((IntOffset) -> Unit)?): Modifier =
    if (onSecondaryClick == null) this else kitPressPoint(rememberPressPoint(), onSecondaryClick, consume = false)
