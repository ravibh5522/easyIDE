package dev.easyide.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The token set a [ThemeMode] resolves to. Pure, so the theme picker can
 * preview a mode without applying it and tests can check every mode.
 *
 * [dynamicAccent] is the wallpaper's primary pair under [ThemeMode.DYNAMIC]:
 * the editor keeps its graphite/paper neutrals and takes only the accent, so
 * syntax contrast never depends on a wallpaper.
 */
fun themeTokensFor(mode: ThemeMode, systemInDark: Boolean, dynamicAccent: Accent? = null): ThemeTokens {
    val palette = paletteFor(mode, systemInDark)
    val tuned = if (mode == ThemeMode.DYNAMIC && dynamicAccent != null) palette.copy(accent = dynamicAccent) else palette
    return tuned.toTokens()
}

/**
 * Applies a [ThemeMode] to everything below it: Material's ColorScheme,
 * typography and shapes for native screens, and [LocalEditorColors] for the
 * workspace - all from one [ThemeTokens], so the two cannot disagree.
 * See docs/decision/0019-visual-identity.md.
 */
@Composable
fun EasyIdeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamicScheme = remember(themeMode, systemInDark, context) {
        if (themeMode == ThemeMode.DYNAMIC) dynamicSchemeOrNull(context, systemInDark) else null
    }
    val tokens = remember(themeMode, systemInDark, dynamicScheme) {
        themeTokensFor(themeMode, systemInDark, dynamicScheme?.let { Accent(it.primary, it.onPrimary) })
    }
    val colorScheme = remember(tokens, dynamicScheme) { dynamicScheme ?: tokens.toColorScheme() }
    val editorColors = remember(tokens) { tokens.toEditorColors() }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EasyIdeTypography,
        shapes = EasyIdeShapes,
    ) {
        CompositionLocalProvider(LocalEditorColors provides editorColors, content = content)
    }
}

/** Wallpaper colours exist from Android 12; below that DYNAMIC falls back to the built-in scheme. */
private fun dynamicSchemeOrNull(context: Context, dark: Boolean): ColorScheme? = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
    dark -> dynamicDarkColorScheme(context)
    else -> dynamicLightColorScheme(context)
}
