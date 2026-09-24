package dev.easyide.app.ui.screens.workspace.zoom

import kotlin.math.roundToInt

/**
 * Pinch-to-zoom of a font size, shared by the editor (a Compose gesture) and the terminal
 * (Termux's scale callback) so both quantise the same way.
 */
object PinchZoom {

    /** [start] scaled by [scale] and rounded to a whole size within `[min, max]`. */
    fun fontSize(start: Int, scale: Float, min: Int, max: Int): Int =
        (start * scale).roundToInt().coerceIn(min, max)
}
