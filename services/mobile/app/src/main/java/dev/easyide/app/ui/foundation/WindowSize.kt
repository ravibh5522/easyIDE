package dev.easyide.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Material 3 width breakpoints. Layout branches on these, never on device type
 * or orientation - a tablet in split-screen is legitimately COMPACT and must
 * render like one. See docs/design-system/arch.md "Responsive design".
 */
enum class WidthClass {
    /** < 600dp: phone, or a narrow split-screen pane. Single column. */
    COMPACT,

    /** 600-839dp: small tablet or half-screen. One side panel at a time. */
    MEDIUM,

    /** >= 840dp: full-screen tablet. All stages can be visible together. */
    EXPANDED;

    val isCompact: Boolean get() = this == COMPACT
    val atLeastMedium: Boolean get() = this != COMPACT
    val isExpanded: Boolean get() = this == EXPANDED
}

/** Height matters for the bottom terminal stage: short windows can't afford it. */
enum class HeightClass {
    COMPACT,
    REGULAR;

    val isCompact: Boolean get() = this == COMPACT
}

data class WindowSize(val width: WidthClass, val height: HeightClass)

private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840
private const val COMPACT_HEIGHT_DP = 480

val LocalWindowSize = staticCompositionLocalOf {
    WindowSize(WidthClass.COMPACT, HeightClass.REGULAR)
}

/**
 * Derived from the configuration rather than the material3-window-size-class
 * artifact so the same value is available outside an Activity scope (previews,
 * nested composables) without threading an Activity reference through.
 */
@Composable
@ReadOnlyComposable
fun currentWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    val width = when {
        configuration.screenWidthDp >= EXPANDED_WIDTH_DP -> WidthClass.EXPANDED
        configuration.screenWidthDp >= MEDIUM_WIDTH_DP -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    val height = if (configuration.screenHeightDp < COMPACT_HEIGHT_DP) {
        HeightClass.COMPACT
    } else {
        HeightClass.REGULAR
    }
    return WindowSize(width, height)
}
