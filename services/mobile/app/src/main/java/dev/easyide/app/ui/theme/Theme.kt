package dev.easyide.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Resolves a [ThemeMode] to a concrete [ColorScheme], all derived from
 * [SeedColor] except DYNAMIC (wallpaper-derived) and AMOLED_BLACK /
 * HIGH_CONTRAST (token overrides on top of the seed-derived scheme).
 * See docs/design-system/arch.md "Themes" and "Color system".
 */
@Composable
private fun resolveColorScheme(mode: ThemeMode, useDarkPalette: Boolean): ColorScheme {
    val context = LocalContext.current
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return when (mode) {
        ThemeMode.SYSTEM_DEFAULT -> {
            if (useDarkPalette) darkColorScheme(primary = SeedColor) else lightColorScheme(primary = SeedColor)
        }

        ThemeMode.LIGHT -> lightColorScheme(primary = SeedColor)

        ThemeMode.DARK -> darkColorScheme(primary = SeedColor)

        ThemeMode.DYNAMIC -> when {
            dynamicSupported && useDarkPalette -> dynamicDarkColorScheme(context)
            dynamicSupported -> dynamicLightColorScheme(context)
            useDarkPalette -> darkColorScheme(primary = SeedColor)
            else -> lightColorScheme(primary = SeedColor)
        }

        ThemeMode.AMOLED_BLACK -> darkColorScheme(primary = SeedColor).copy(
            surface = AmoledSurface,
            surfaceDim = AmoledSurfaceDim,
            surfaceContainerLowest = AmoledSurfaceContainerLowest,
            onSurface = AmoledOnSurface,
        )

        // Simplified boosted-contrast starting point - pending real WCAG
        // validation, tracked in docs/design-system/tracker.md.
        ThemeMode.HIGH_CONTRAST -> if (useDarkPalette) {
            darkColorScheme(primary = SeedColor, background = AmoledSurface, onBackground = AmoledOnSurface)
        } else {
            lightColorScheme(primary = SeedColor)
        }
    }
}

@Composable
fun EasyIdeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    content: @Composable () -> Unit,
) {
    val useDarkPalette = isSystemInDarkTheme()
    val colorScheme = resolveColorScheme(themeMode, useDarkPalette)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EasyIdeTypography,
        shapes = EasyIdeShapes,
        content = content,
    )
}
