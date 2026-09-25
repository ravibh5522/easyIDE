package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Opens a row's action menu on a long press or a secondary-button press, the two ways a tablet
 * offers one. It watches on the initial pass, before the row's own tap handling, and consumes
 * the rest of that press so the row does not also open the project when the finger lifts.
 * An ordinary tap or a scroll passes through untouched.
 */
internal fun Modifier.contextMenuTrigger(onTrigger: () -> Unit): Modifier = pointerInput(onTrigger) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val secondary = currentEvent.buttons.isSecondaryPressed
        if (secondary || awaitLongPressOrCancellation(down.id) != null) {
            onTrigger()
            waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
        }
    }
}
