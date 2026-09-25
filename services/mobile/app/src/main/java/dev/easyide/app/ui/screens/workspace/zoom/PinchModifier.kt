package dev.easyide.app.ui.screens.workspace.zoom

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Two-finger pinch on the content below, zooming [zoom]. Watches in the Initial pass and
 * consumes only once a second finger is down, so a single finger still places the caret,
 * selects and scrolls exactly as before; consuming the two-finger events is what keeps the
 * surrounding scroll containers from also panning during the pinch.
 *
 * [zoom] is a new instance whenever the size changes, so the gesture reads the latest one
 * through [rememberUpdatedState] instead of capturing the instance it started with.
 */
fun Modifier.pinchToZoom(zoom: FontZoom): Modifier = composed {
    val current by rememberUpdatedState(zoom)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startDistance = 0f
            var startSize = current.size
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.size >= 2) {
                    val distance = (down[0].position - down[1].position).getDistance()
                    if (startDistance == 0f) {
                        startDistance = distance
                        startSize = current.size
                    } else {
                        current.zoomTo(startSize, distance / startDistance)
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
