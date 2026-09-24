package dev.easyide.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.easyide.app.R
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
 * The dense IDE scale from ux-overhaul Pillar 3: an IDE shows far more text per
 * screen than a consumer app, so Material's 14-16sp body sizes are stepped down.
 */
object TypeScale {
    val label = 11.sp
    val dense = 12.sp
    val body = 13.sp
    val title = 15.sp
    val screenTitle = 20.sp
    val display = 28.sp

    /** Tracking for all-caps section headers, so short caps words stay legible at 11sp. */
    val capsTracking = 0.6.sp
}

private fun style(family: FontFamily, size: TextUnit, lineHeight: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

/**
 * Material roles mapped onto [TypeScale]. Components pick roles (a TopAppBar
 * title is `titleLarge`, a ListItem headline `bodyLarge`), so the mapping, not
 * call sites, decides that a screen title is 20sp and body text 13sp.
 */
fun easyIdeTypography(pairing: FontPairing): Typography {
    val family = EasyIdeFonts.chrome(pairing)
    return Typography(
        displayLarge = style(family, TypeScale.display, 34.sp, FontWeight.SemiBold),
        displayMedium = style(family, TypeScale.display, 34.sp, FontWeight.SemiBold),
        displaySmall = style(family, TypeScale.display, 34.sp, FontWeight.Medium),
        headlineLarge = style(family, TypeScale.screenTitle, 26.sp, FontWeight.SemiBold),
        headlineMedium = style(family, TypeScale.screenTitle, 26.sp, FontWeight.SemiBold),
        headlineSmall = style(family, TypeScale.screenTitle, 26.sp, FontWeight.Medium),
        titleLarge = style(family, TypeScale.screenTitle, 26.sp, FontWeight.Medium),
        titleMedium = style(family, TypeScale.title, 20.sp, FontWeight.Medium),
        titleSmall = style(family, TypeScale.body, 18.sp, FontWeight.Medium),
        bodyLarge = style(family, TypeScale.body, 20.sp, FontWeight.Normal),
        bodyMedium = style(family, TypeScale.body, 18.sp, FontWeight.Normal),
        bodySmall = style(family, TypeScale.dense, 16.sp, FontWeight.Normal),
        labelLarge = style(family, TypeScale.body, 18.sp, FontWeight.Medium),
        labelMedium = style(family, TypeScale.dense, 16.sp, FontWeight.Medium),
        labelSmall = style(family, TypeScale.label, 16.sp, FontWeight.Medium),
    )
}

/** 11sp caps header ("SOURCE CONTROL", "STAGED CHANGES"); the caller uppercases the text. */
val Typography.sectionHeader: TextStyle
    get() = labelSmall.copy(letterSpacing = TypeScale.capsTracking)

/** Tabular figures, so counts and Ln/Col in the status bar do not jitter as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

private const val TABULAR_FIGURES = "tnum"
