package dev.easyide.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.easyide.app.R

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

private fun style(size: TextUnit, lineHeight: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = EasyIdeFonts.sans,
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
val EasyIdeTypography = Typography(
    displayLarge = style(TypeScale.display, 34.sp, FontWeight.SemiBold),
    displayMedium = style(TypeScale.display, 34.sp, FontWeight.SemiBold),
    displaySmall = style(TypeScale.display, 34.sp, FontWeight.Medium),
    headlineLarge = style(TypeScale.screenTitle, 26.sp, FontWeight.SemiBold),
    headlineMedium = style(TypeScale.screenTitle, 26.sp, FontWeight.SemiBold),
    headlineSmall = style(TypeScale.screenTitle, 26.sp, FontWeight.Medium),
    titleLarge = style(TypeScale.screenTitle, 26.sp, FontWeight.Medium),
    titleMedium = style(TypeScale.title, 20.sp, FontWeight.Medium),
    titleSmall = style(TypeScale.body, 18.sp, FontWeight.Medium),
    bodyLarge = style(TypeScale.body, 20.sp, FontWeight.Normal),
    bodyMedium = style(TypeScale.body, 18.sp, FontWeight.Normal),
    bodySmall = style(TypeScale.dense, 16.sp, FontWeight.Normal),
    labelLarge = style(TypeScale.body, 18.sp, FontWeight.Medium),
    labelMedium = style(TypeScale.dense, 16.sp, FontWeight.Medium),
    labelSmall = style(TypeScale.label, 16.sp, FontWeight.Medium),
)

/** 11sp caps header ("SOURCE CONTROL", "STAGED CHANGES"); the caller uppercases the text. */
val Typography.sectionHeader: TextStyle
    get() = labelSmall.copy(letterSpacing = TypeScale.capsTracking)

/** Tabular figures, so counts and Ln/Col in the status bar do not jitter as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

private const val TABULAR_FIGURES = "tnum"
