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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.foundation.systemMotionEnabled
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.Feel
import dev.easyide.app.ui.props.LocalFeel
import dev.easyide.app.ui.props.LocalMetrics
import dev.easyide.app.ui.props.LocalMotion
import dev.easyide.app.ui.props.Motion
import dev.easyide.app.ui.props.UiMetrics
import dev.easyide.app.ui.theme.AccentDerivation.withAccent

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
 *
 * [appearance] is the resolved design properties: the accent is laid over the palette (never over
 * the high-contrast mode, which wins), the scale multiplies density and font scale for everything
 * below, and metrics, motion and feel reach components through their composition locals.
 *
 * [contributed] is an extension colour theme already laid over its base palette
 * (`workbench.colorTheme`, customization.md sec 8); when set it replaces the
 * [themeMode] palette entirely, wallpaper accent included.
 */
@Composable
fun EasyIdeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    contributed: ThemeTokens? = null,
    /** `workbench.colorCustomizations` and friends, laid over whichever palette is active. */
    customizations: ColorCustomizations = ColorCustomizations.NONE,
    /** The active theme's label, selecting `"[label]"` blocks of [customizations]. */
    themeLabel: String? = null,
    appearance: Appearance = Appearance.DEFAULT,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamicScheme = remember(themeMode, systemInDark, context, contributed) {
        if (themeMode == ThemeMode.DYNAMIC && contributed == null) dynamicSchemeOrNull(context, systemInDark) else null
    }
    val tokens = remember(themeMode, systemInDark, dynamicScheme, contributed) {
        contributed ?: themeTokensFor(themeMode, systemInDark, dynamicScheme?.let { Accent(it.primary, it.onPrimary) })
    }
    val accentColor = remember(appearance.accent, themeMode, contributed, dynamicScheme, systemInDark, context) {
        accentOverride(appearance.accent, themeMode, contributed, dynamicScheme ?: dynamicSchemeOrNull(context, systemInDark))
    }
    val accented = remember(tokens, accentColor) { accentColor?.let { tokens.withAccent(it) } ?: tokens }
    val customized = remember(accented, customizations, themeLabel) { ThemeCustomizer.apply(accented, customizations, themeLabel).tokens }
    val colorScheme = remember(customized, dynamicScheme) { dynamicScheme?.takeIf { accentColor == null } ?: customized.toColorScheme() }
    val editorColors = remember(customized) { customized.toEditorColors() }

    val metrics = remember(appearance) { UiMetrics.of(appearance) }
    val systemReducesMotion = remember { !systemMotionEnabled() }
    val motion = remember(appearance, systemReducesMotion) { Motion.of(appearance, systemReducesMotion) }
    val feel = remember(appearance) { Feel.of(appearance) }
    val typography = remember(appearance.fontPairing) { easyIdeTypography(appearance.fontPairing) }
    val shapes = remember(metrics) { easyIdeShapes(metrics) }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, appearance.uiScale) {
        Density(baseDensity.density * appearance.uiScale, baseDensity.fontScale * appearance.uiScale)
    }

    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(
            LocalEditorColors provides editorColors,
            LocalMetrics provides metrics,
            LocalMotion provides motion,
            LocalFeel provides feel,
            LocalMotionEnabled provides !motion.reduce,
            LocalDensity provides scaledDensity,
            content = content,
        )
    }
}

/**
 * The colour to lay over the palette's accent, or null to keep the palette's own. High contrast
 * always keeps its own (its accent is tuned to AAA on pure black or white), and `wallpaper`
 * needs Android 12; without it the palette accent stays.
 */
private fun accentOverride(choice: AccentChoice, mode: ThemeMode, contributed: ThemeTokens?, wallpaper: ColorScheme?): Color? = when {
    mode == ThemeMode.HIGH_CONTRAST && contributed == null -> null
    choice is AccentChoice.Custom -> Color(0xFF000000.toInt() or choice.rgb)
    choice is AccentChoice.Wallpaper && mode != ThemeMode.DYNAMIC -> wallpaper?.primary
    else -> null
}

/** Wallpaper colours exist from Android 12; below that DYNAMIC falls back to the built-in scheme. */
private fun dynamicSchemeOrNull(context: Context, dark: Boolean): ColorScheme? = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
    dark -> dynamicDarkColorScheme(context)
    else -> dynamicLightColorScheme(context)
}
