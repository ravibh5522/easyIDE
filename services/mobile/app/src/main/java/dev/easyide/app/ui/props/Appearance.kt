package dev.easyide.app.ui.props

import androidx.compose.runtime.Immutable
import dev.easyide.app.ui.foundation.WidthClass

/*
 * The design properties of docs/ui-redesign/properties.md as values. Every choice carries an
 * explicit string [id]: that spelling is what settings.json stores, and R8 renames enum
 * constants in release builds, so `name` and `ordinal` never reach a file.
 */

/** Resolved row and control density. [DENSE] is the old `compact` step, made VS Code sized (density.md). */
enum class Density(val id: String) {
    DENSE("dense"), COMFORTABLE("comfortable"), SPACIOUS("spacious");

    companion object {
        /** What `appearance.density = auto` resolves to: a phone keeps finger-sized rows, a tablet is dense. */
        fun forWidth(width: WidthClass): Density = if (width.isCompact) COMFORTABLE else DENSE
    }
}

/**
 * What the user picked for `appearance.density`: a fixed step, or [AUTO] which follows the width
 * class. Stored by [id]; `compact` (the pre-density.md spelling of [DENSE]) still decodes.
 */
enum class DensityPref(val id: String, val density: Density?) {
    AUTO("auto", null), DENSE("dense", Density.DENSE), COMFORTABLE("comfortable", Density.COMFORTABLE), SPACIOUS("spacious", Density.SPACIOUS);

    companion object {
        const val LEGACY_COMPACT = "compact"
        val entriesById: Map<String, DensityPref> = entries.associateBy { it.id } + (LEGACY_COMPACT to DENSE)
    }
}

enum class Corners(val id: String) { SHARP("sharp"), SOFT("soft"), ROUND("round") }

enum class FontPairing(val id: String) { GEIST("geist"), MONO_CHROME("monoChrome"), SYSTEM("system") }

enum class ChromeContrast(val id: String) { SOFT("soft"), NORMAL("normal"), HIGH("high") }

enum class Motif(val id: String) { OFF("off"), SUBTLE("subtle"), FULL("full") }

enum class HapticsLevel(val id: String) { OFF("off"), SUBTLE("subtle"), FULL("full") }

enum class ReduceMotion(val id: String) { SYSTEM("system"), ON("on"), OFF("off") }

enum class IconStyle(val id: String) { EI("ei"), MATERIAL("material") }

enum class Handedness(val id: String) { RIGHT("right"), LEFT("left") }

/** Where the accent comes from: the palette, the wallpaper (Android 12+), or a chosen colour. */
sealed interface AccentChoice {
    data object Theme : AccentChoice
    data object Wallpaper : AccentChoice

    /** Opaque RGB; alpha in the stored hex is dropped (properties.md 3). */
    data class Custom(val rgb: Int) : AccentChoice

    val id: String
        get() = when (this) {
            Theme -> THEME
            Wallpaper -> WALLPAPER
            is Custom -> "#%06X".format(rgb and RGB_MASK)
        }

    companion object {
        const val THEME = "theme"
        const val WALLPAPER = "wallpaper"
        private const val RGB_MASK = 0xFFFFFF

        /** Accepts `theme`, `wallpaper` and `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`; anything else is null. */
        fun parse(raw: String): AccentChoice? = when (val text = raw.trim()) {
            THEME -> Theme
            WALLPAPER -> Wallpaper
            else -> parseHex(text)?.let(::Custom)
        }

        /** Same shapes as `dev.easyide.app.ui.theme.parseHexColor`, kept Compose-free so it unit-tests on the JVM. */
        private fun parseHex(text: String): Int? {
            val hex = text.removePrefix("#")
            if (!text.startsWith("#") || hex.any { it.digitToIntOrNull(HEX_RADIX) == null }) return null
            val full = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            return full.take(6).toInt(HEX_RADIX)
        }

        private const val HEX_RADIX = 16
    }
}

/**
 * The resolved, validated design properties. Immutable and cheap to compare, so the theme can
 * key its `remember` blocks on it. [DEFAULT] reproduces the app exactly as it looked before the
 * properties existed (a test pins that against the old constants).
 */
@Immutable
data class Appearance(
    val accent: AccentChoice = AccentChoice.Theme,
    /** Null is `auto`: [Density.forWidth] of the window. */
    val density: Density? = null,
    val corners: Corners = Corners.SOFT,
    /** Percent, 85 to 130: scales dp and sp together. */
    val uiScalePercent: Int = UI_SCALE_DEFAULT,
    val fontPairing: FontPairing = FontPairing.SYSTEM,
    val chromeContrast: ChromeContrast = ChromeContrast.NORMAL,
    val motif: Motif = Motif.SUBTLE,
    val cursorBlink: Boolean = true,
    val reduceMotion: ReduceMotion = ReduceMotion.SYSTEM,
    val haptics: HapticsLevel = HapticsLevel.SUBTLE,
    val iconStyle: IconStyle = IconStyle.EI,
    val handedness: Handedness = Handedness.RIGHT,
    /** Hide the system bars (swipe from an edge to peek at them), so the app owns the whole screen. */
    val fullScreen: Boolean = true,
) {
    val uiScale: Float get() = uiScalePercent / PERCENT

    companion object {
        val DEFAULT = Appearance()
        const val UI_SCALE_MIN = 85
        const val UI_SCALE_MAX = 130
        const val UI_SCALE_STEP = 5
        const val UI_SCALE_DEFAULT = 100
        private const val PERCENT = 100f
    }
}
