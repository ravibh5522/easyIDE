package dev.easyide.app.ui.props

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable

/**
 * The motion table of identity.md 7. Three durations, one ceiling, hard-step blink. With
 * [reduce] every duration is 0 and the cursor stays solid, so no animation site needs a branch.
 */
@Immutable
data class Motion(val reduce: Boolean, val blink: Boolean) {
    val pressMs: Int get() = if (reduce) 0 else PRESS_MS
    val standardMs: Int get() = if (reduce) 0 else STANDARD_MS
    val paneMs: Int get() = if (reduce) 0 else PANE_MS

    /** The cursor blinks only when the property is on and motion is not reduced. */
    val cursorBlinks: Boolean get() = blink && !reduce

    val enter: Easing get() = ENTER
    val exit: Easing get() = EXIT
    val press: Easing get() = LinearEasing

    companion object {
        const val PRESS_MS = 90
        const val STANDARD_MS = 140
        const val PANE_MS = 220
        const val CEILING_MS = 320

        /** Cursor half-period: on for this long, then off for this long, no fade. */
        const val BLINK_HALF_MS = 530

        /** A blink pauses solid for this long after any input. */
        const val BLINK_RESET_MS = 600

        /** Home and empty-state header cursors blink this many cycles, then rest solid. */
        const val HEADER_BLINK_CYCLES = 6

        private val ENTER = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        private val EXIT = CubicBezierEasing(0.3f, 0f, 1f, 1f)

        /** [systemReduces] is the platform "remove animations" state, consulted for [ReduceMotion.SYSTEM]. */
        fun of(appearance: Appearance, systemReduces: Boolean, screenReaderOn: Boolean = false): Motion {
            val reduce = when (appearance.reduceMotion) {
                ReduceMotion.SYSTEM -> systemReduces
                ReduceMotion.ON -> true
                ReduceMotion.OFF -> false
            }
            return Motion(reduce = reduce, blink = appearance.cursorBlink && !screenReaderOn)
        }

        val DEFAULT = Motion(reduce = false, blink = true)
    }
}

/** How the app feels rather than looks: haptics, the supporting motifs, glyph set and handedness. */
@Immutable
data class Feel(
    val haptics: HapticsLevel,
    val motif: Motif,
    val iconStyle: IconStyle,
    val handedness: Handedness,
) {
    companion object {
        fun of(appearance: Appearance) = Feel(appearance.haptics, appearance.motif, appearance.iconStyle, appearance.handedness)

        val DEFAULT = of(Appearance.DEFAULT)
    }
}
