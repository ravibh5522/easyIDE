package dev.tabcode.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Placeholder brand seed color pending a real palette decision - see the
 * "Open questions" section of docs/design-system/arch.md. Every ColorScheme
 * in Theme.kt is derived from this single seed rather than hand-picked colors,
 * so replacing this one value re-themes the whole app.
 */
val SeedColor = Color(0xFF6750A4)

/**
 * True-black surface overrides for the AMOLED theme. Material 3's generated
 * dark scheme uses dark-gray surfaces by default; these override just the
 * surface family to pure black for OLED power savings, per
 * docs/design-system/arch.md "AMOLED Black".
 */
val AmoledSurface = Color(0xFF000000)
val AmoledSurfaceDim = Color(0xFF000000)
val AmoledSurfaceContainerLowest = Color(0xFF000000)
val AmoledOnSurface = Color(0xFFE6E1E5)
