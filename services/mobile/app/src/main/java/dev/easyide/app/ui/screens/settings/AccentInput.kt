package dev.easyide.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import dev.easyide.app.R
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.theme.AccentDerivation
import dev.easyide.app.ui.theme.Hsl
import kotlin.math.abs

/** A named accent offered as a swatch (identity.md 4). A null [rgb] is the mono swatch: the theme's ink, read at draw time. */
data class AccentSwatch(@StringRes val label: Int, val rgb: Int?)

/** What the accent field tells the user about the text they typed. */
data class AccentReport(
    /** Null while the text is not a valid accent. */
    val choice: AccentChoice?,
    /** The colour the interface will actually use: the guard's nudge of a custom colour. */
    val shown: Color?,
    /** The custom colour was too weak on the panel or editor and was moved in lightness. */
    val adjusted: Boolean,
    /** The custom hue is within the margin of a signal colour (state, git). Warn, never block. */
    val nearSignal: Boolean,
)

object AccentInput {

    val SWATCHES = listOf(
        AccentSwatch(R.string.accent_orange, 0xFF8A3D),
        AccentSwatch(R.string.accent_iris, 0x7C8CFF),
        AccentSwatch(R.string.accent_violet, 0xB18CFF),
        AccentSwatch(R.string.accent_teal, 0x3CC9C0),
        AccentSwatch(R.string.accent_sky, 0x5CB8FF),
        AccentSwatch(R.string.accent_rose, 0xFF7AA8),
        AccentSwatch(R.string.accent_mono, null),
    )

    /** properties.md 3: an accent this close to a signal hue warns. */
    const val SIGNAL_HUE_MARGIN_DEGREES = 20f

    /** Below this saturation a colour is grey and has no hue worth comparing. */
    private const val MIN_SATURATION = 0.15f
    private const val FULL_TURN = 360f
    private const val RGB_MASK = 0xFFFFFF

    fun report(text: String, panel: Color, editor: Color, signals: List<Color>): AccentReport {
        val choice = AccentChoice.parse(text) ?: return AccentReport(null, null, adjusted = false, nearSignal = false)
        val custom = choice as? AccentChoice.Custom ?: return AccentReport(choice, null, adjusted = false, nearSignal = false)
        val chosen = Color(custom.rgb and RGB_MASK or OPAQUE)
        val shown = AccentDerivation.guard(chosen, panel, editor)
        return AccentReport(choice, shown, adjusted = shown != chosen, nearSignal = nearSignal(chosen, signals))
    }

    fun nearSignal(accent: Color, signals: List<Color>): Boolean {
        val a = Hsl.of(accent)
        if (a.s < MIN_SATURATION) return false
        return signals.any { s ->
            val h = Hsl.of(s)
            h.s >= MIN_SATURATION && hueDistance(a.h, h.h) < SIGNAL_HUE_MARGIN_DEGREES
        }
    }

    /** The shorter way round the hue circle, 0 to 180 degrees. */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % FULL_TURN
        return if (d > FULL_TURN / 2) FULL_TURN - d else d
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
