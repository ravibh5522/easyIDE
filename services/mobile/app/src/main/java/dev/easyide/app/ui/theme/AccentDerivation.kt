package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a user-chosen accent into the token family the built-in palettes derive from one colour
 * (docs/ui-redesign/properties.md 6.1). Neutrals, syntax and the terminal ANSI palette are never
 * touched. The contrast guard applies to every source of an accent, so no choice can make focus
 * rings, the active tab bar or primary buttons unreadable.
 */
object AccentDerivation {

    const val MIN_ON_PANEL = 4.5
    const val MIN_ON_EDITOR = 3.0

    private const val NUDGE_STEP = 0.02f
    private const val MAX_STEPS = 60
    private const val LIST_SELECTION_DARK = 0.18f
    private const val LIST_SELECTION_LIGHT = 0.16f

    /** The accent family over [this]; [chosen] is first nudged in lightness until it is readable on the panel and editor. */
    fun ThemeTokens.withAccent(chosen: Color): ThemeTokens {
        val panel = this[ColorToken.PANEL]
        val accent = guard(chosen.copy(alpha = 1f), panel, this[ColorToken.EDITOR_BACKGROUND])
        val listAlpha = if (isDark) LIST_SELECTION_DARK else LIST_SELECTION_LIGHT
        return withOverrides(
            mapOf(
                ColorToken.ACCENT to accent,
                ColorToken.ON_ACCENT to onAccentFor(accent),
                ColorToken.FOCUS_BORDER to accent,
                ColorToken.TAB_ACTIVE_BORDER to accent,
                ColorToken.ACTIVITY_BAR_ACTIVE_BORDER to accent,
                ColorToken.CURSOR to accent,
                ColorToken.TERMINAL_CURSOR to accent,
                ColorToken.SELECTION to accent.copy(alpha = this[ColorToken.SELECTION].alpha),
                ColorToken.BRACKET_MATCH to accent.copy(alpha = this[ColorToken.BRACKET_MATCH].alpha),
                ColorToken.LIST_ACTIVE_SELECTION to accent.copy(alpha = listAlpha).compositeOver(panel),
            ),
        )
    }

    /** Lightens on a dark panel and darkens on a light one until both minimums hold (or the ramp runs out). */
    fun guard(accent: Color, panel: Color, editor: Color): Color {
        if (passes(accent, panel, editor)) return accent
        val towardWhite = panel.luminance() < 0.5f
        var hsl = Hsl.of(accent)
        repeat(MAX_STEPS) {
            hsl = hsl.copy(l = (hsl.l + if (towardWhite) NUDGE_STEP else -NUDGE_STEP).coerceIn(0f, 1f))
            val candidate = hsl.toColor()
            if (passes(candidate, panel, editor)) return candidate
        }
        return hsl.toColor()
    }

    /** Near-black or white, whichever reads better on [accent]. */
    fun onAccentFor(accent: Color): Color =
        if (contrast(accent, DARK_ON) >= contrast(accent, LIGHT_ON)) DARK_ON else LIGHT_ON

    /** WCAG 2.x contrast ratio, 1.0 to 21.0. */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Readable on both surfaces, and the label drawn on it (primary buttons) reaches AA too. */
    private fun passes(accent: Color, panel: Color, editor: Color) =
        contrast(accent, panel) >= MIN_ON_PANEL && contrast(accent, editor) >= MIN_ON_EDITOR &&
            contrast(accent, onAccentFor(accent)) >= MIN_ON_PANEL

    private val DARK_ON = Color(0xFF0E1014)
    private val LIGHT_ON = Color(0xFFFFFFFF)
}

/** Hue 0-360, saturation and lightness 0-1: just enough to move lightness without shifting the hue. */
internal data class Hsl(val h: Float, val s: Float, val l: Float) {
    fun toColor(): Color {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }

    companion object {
        fun of(color: Color): Hsl {
            val r = color.red
            val g = color.green
            val b = color.blue
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val l = (hi + lo) / 2f
            val d = hi - lo
            if (d == 0f) return Hsl(0f, 0f, l)
            val s = d / (1f - abs(2f * l - 1f))
            val h = when (hi) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            return Hsl(if (h < 0f) h + 360f else h, s, l)
        }
    }
}
