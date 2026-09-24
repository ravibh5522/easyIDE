package dev.easyide.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Material 3 roles read from the same tokens as the workspace, so Home,
 * Settings and dialogs wear the graphite/paper neutrals and the one accent
 * instead of a seed-generated purple family.
 *
 * Deliberate choices:
 * - `secondary` is neutral, not a second hue: the design has exactly one accent.
 * - `surfaceTint` equals `surface`, which turns Material's tonal-elevation tint
 *   into a no-op; tonal steps come from the explicit container roles instead.
 * - Containers: cards and filled fields (`surfaceContainerHighest`) sit on
 *   [ColorToken.RAISED]; menus and dialogs (`surfaceContainer`/`High`) on
 *   [ColorToken.OVERLAY]; sheets (`surfaceContainerLow`) on [ColorToken.PANEL].
 */
fun ThemeTokens.toColorScheme(): ColorScheme {
    val panel = this[ColorToken.PANEL]
    val foreground = this[ColorToken.FOREGROUND]
    val background = this[ColorToken.EDITOR_BACKGROUND]
    fun container(color: Color) = color.copy(alpha = CONTAINER_TINT_ALPHA).compositeOver(panel)

    val accent = this[ColorToken.ACCENT]
    val muted = this[ColorToken.TEXT_MUTED]
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    // copy() with every role named: a role left out would keep Material's
    // baseline purple, which is exactly the look this replaces.
    return base.copy(
        primary = accent,
        onPrimary = this[ColorToken.ON_ACCENT],
        primaryContainer = container(accent),
        onPrimaryContainer = foreground,
        inversePrimary = accent,
        secondary = muted,
        onSecondary = background,
        secondaryContainer = this[ColorToken.RAISED],
        onSecondaryContainer = foreground,
        tertiary = this[ColorToken.INFO],
        onTertiary = background,
        tertiaryContainer = container(this[ColorToken.INFO]),
        onTertiaryContainer = foreground,
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = this[ColorToken.RAISED],
        onSurfaceVariant = muted,
        surfaceTint = background,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = this[ColorToken.ERROR],
        onError = background,
        errorContainer = container(this[ColorToken.ERROR]),
        onErrorContainer = foreground,
        outline = this[ColorToken.TEXT_DISABLED],
        outlineVariant = this[ColorToken.HAIRLINE],
        scrim = SCRIM,
        surfaceBright = this[ColorToken.OVERLAY],
        surfaceContainer = this[ColorToken.OVERLAY],
        surfaceContainerHigh = this[ColorToken.OVERLAY],
        surfaceContainerHighest = this[ColorToken.RAISED],
        surfaceContainerLow = panel,
        surfaceContainerLowest = background,
        surfaceDim = background,
        primaryFixed = container(accent),
        primaryFixedDim = accent,
        onPrimaryFixed = foreground,
        onPrimaryFixedVariant = muted,
        secondaryFixed = this[ColorToken.RAISED],
        secondaryFixedDim = this[ColorToken.OVERLAY],
        onSecondaryFixed = foreground,
        onSecondaryFixedVariant = muted,
        tertiaryFixed = container(this[ColorToken.INFO]),
        tertiaryFixedDim = this[ColorToken.INFO],
        onTertiaryFixed = foreground,
        onTertiaryFixedVariant = muted,
    )
}

/** How strongly a signal colour tints its `*Container` role over the panel. */
private const val CONTAINER_TINT_ALPHA = 0.18f

/** Material's scrim is black in every scheme; the dialog dim is applied as alpha by the component. */
private val SCRIM = Color(0xFF000000)
