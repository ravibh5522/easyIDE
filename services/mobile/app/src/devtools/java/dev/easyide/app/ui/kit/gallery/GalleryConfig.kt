package dev.easyide.app.ui.kit.gallery

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.easyide.app.R
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.Corners
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.ReduceMotion
import dev.easyide.app.ui.theme.ThemeMode

/**
 * What the gallery's live controls hold. Local to the screen and never stored: the real
 * settings are untouched. [appearance] is what the gallery hands to `EasyIdeTheme`, the same
 * entry the app uses. [fontScale] is not an appearance property (it is the system font size), so
 * the screen applies it to the density above the theme.
 */
@Immutable
data class GalleryConfig(
    /** Null is auto: the density of the window, as in the app. */
    val density: Density? = null,
    val corners: Corners = Corners.SOFT,
    val fontScale: Float = GalleryOptions.FONT_SCALES.first(),
    val accent: AccentChoice = AccentChoice.Theme,
    val mode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    val reduceMotion: Boolean = false,
    val motif: Motif = Motif.SUBTLE,
) {
    /** Reduce motion off leaves the choice to the system, so the gallery never overrides an accessibility setting downwards. */
    fun appearance() = Appearance(
        accent = accent,
        density = density,
        corners = corners,
        motif = motif,
        reduceMotion = if (reduceMotion) ReduceMotion.ON else ReduceMotion.SYSTEM,
    )
}

/** A choice and the string that names it. Labels are resources; R8 renames enum constants, so never `name`. */
class Option<out T>(val value: T, @StringRes val label: Int)

/** The single table of what each live control offers. */
object GalleryOptions {
    val DENSITIES: List<Option<Density?>> = listOf(
        Option(null, R.string.gallery_density_auto),
        Option(Density.DENSE, R.string.gallery_density_dense),
        Option(Density.COMFORTABLE, R.string.gallery_density_comfortable),
        Option(Density.SPACIOUS, R.string.gallery_density_spacious),
    )
    val CORNERS = listOf(
        Option(Corners.SHARP, R.string.gallery_corners_sharp),
        Option(Corners.SOFT, R.string.gallery_corners_soft),
        Option(Corners.ROUND, R.string.gallery_corners_round),
    )
    val MODES = listOf(
        Option(ThemeMode.SYSTEM_DEFAULT, R.string.gallery_mode_system),
        Option(ThemeMode.LIGHT, R.string.gallery_mode_light),
        Option(ThemeMode.DARK, R.string.gallery_mode_dark),
        Option(ThemeMode.AMOLED_BLACK, R.string.gallery_mode_amoled),
    )
    val MOTIFS = listOf(
        Option(Motif.OFF, R.string.gallery_motif_off),
        Option(Motif.SUBTLE, R.string.gallery_motif_subtle),
        Option(Motif.FULL, R.string.gallery_motif_full),
    )

    /** The system font sizes that matter for layout: default, the common large step, and the maximum. */
    val FONT_SCALES = listOf(1f, 1.3f, 2f)

    /** The palette's own accent plus a spread of hues, to see contrast and tone separation change with it. */
    val ACCENTS = listOf(
        Option(AccentChoice.Theme, R.string.gallery_accent_default),
        Option(AccentChoice.Custom(0x3B82F6), R.string.gallery_accent_blue),
        Option(AccentChoice.Custom(0x8B5CF6), R.string.gallery_accent_violet),
        Option(AccentChoice.Custom(0x14B8A6), R.string.gallery_accent_teal),
        Option(AccentChoice.Custom(0xEC4899), R.string.gallery_accent_pink),
    )
}
