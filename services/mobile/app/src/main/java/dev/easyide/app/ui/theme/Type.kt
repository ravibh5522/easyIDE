package dev.easyide.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitText
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.FontPairing

/**
 * Bundled families (decision 0019): Geist for UI, Geist Mono for code and the
 * terminal. Both OFL-1.1, from vercel/geist-font v1.7.2, hinting stripped at
 * bundle time. Only the weights the scale below uses ship; a weight in between
 * resolves to the nearest bundled one.
 */
object EasyIdeFonts {
    val sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
    )

    val mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_bold, FontWeight.Bold),
    )

    /** The family the interface (not the editor) is set in for a pairing. The editor and terminal keep [mono]. */
    fun chrome(pairing: FontPairing): FontFamily = when (pairing) {
        FontPairing.GEIST -> sans
        FontPairing.MONO_CHROME -> mono
        FontPairing.SYSTEM -> FontFamily.Default
    }

    /** The interface's monospace face: measured values, paths, ids. */
    fun chromeMono(pairing: FontPairing): FontFamily = if (pairing == FontPairing.SYSTEM) FontFamily.Monospace else mono
}

/**
 * The five type steps of one density, in sp (density.md 2). Every role of [dev.easyide.app.ui.kit.KitText]
 * maps onto exactly one step, so a caption is the same size on every screen. The editor and terminal
 * sizes are user settings and are not part of this scale.
 */
data class TypeSteps(val label: TextUnit, val caption: TextUnit, val body: TextUnit, val heading: TextUnit, val display: TextUnit) {
    val all: List<TextUnit> get() = listOf(label, caption, body, heading, display)
}

object TypeScale {
    /** Tracking for all-caps section headers, so short caps words stay legible at 11sp. */
    val capsTracking = 0.6.sp

    fun steps(density: Density): TypeSteps = when (density) {
        Density.DENSE -> TypeSteps(11.sp, 12.sp, 13.sp, 15.sp, 20.sp)
        Density.COMFORTABLE -> TypeSteps(12.sp, 13.sp, 14.sp, 16.sp, 20.sp)
        Density.SPACIOUS -> TypeSteps(13.sp, 14.sp, 15.sp, 17.sp, 22.sp)
    }
}

/**
 * Material roles mapped onto the [dev.easyide.app.ui.kit.KitText] roles, so a stock Material
 * component and a kit component agree on a size. The kit reads `Kit.text`, never these.
 */
fun easyIdeTypography(text: KitText): Typography = Typography(
    displayLarge = text.display, displayMedium = text.display, displaySmall = text.display,
    headlineLarge = text.display, headlineMedium = text.display, headlineSmall = text.display,
    titleLarge = text.display, titleMedium = text.heading, titleSmall = text.title,
    bodyLarge = text.body, bodyMedium = text.body, bodySmall = text.caption,
    labelLarge = text.title, labelMedium = text.caption, labelSmall = text.label,
)

/** Tabular figures, so counts and Ln/Col in the status bar do not jitter as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

private const val TABULAR_FIGURES = "tnum"
