package dev.easyide.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Code-surface colors. The IDE chrome needs a denser, flatter palette than
 * Material's tonal surfaces give - editors want near-uniform backgrounds so
 * syntax colors carry the contrast, not the panels.
 *
 * Kept as an explicit token set (rather than raw hex at call sites) so the
 * light/dark variants stay in step, per docs/design-system/arch.md.
 */
data class EditorColors(
    val background: Color,
    val gutter: Color,
    val gutterText: Color,
    val panel: Color,
    val panelBorder: Color,
    val activityBar: Color,
    val statusBar: Color,
    val statusBarText: Color,
    val tabActive: Color,
    val tabInactive: Color,
    val plainText: Color,
    val syntax: SyntaxColors,
    val terminalText: Color,
    val terminalPrompt: Color,
    val terminalBackground: Color,
)

private val DarkEditorColors = EditorColors(
    background = Color(0xFF1E1E1E),
    gutter = Color(0xFF1E1E1E),
    gutterText = Color(0xFF858585),
    panel = Color(0xFF252526),
    panelBorder = Color(0xFF3C3C3C),
    activityBar = Color(0xFF333333),
    statusBar = Color(0xFF007ACC),
    statusBarText = Color(0xFFFFFFFF),
    tabActive = Color(0xFF1E1E1E),
    tabInactive = Color(0xFF2D2D2D),
    plainText = Color(0xFFD4D4D4),
    syntax = DarkSyntaxColors,
    terminalText = Color(0xFFCCCCCC),
    terminalPrompt = Color(0xFF4EC9B0),
    // Matches com.termux.terminal.TerminalColorScheme's own default
    // background - the vendored terminal-emulator paints its own colors
    // natively and isn't theme-aware yet, so this is fixed, not derived.
    terminalBackground = Color(0xFF000000),
)

private val LightEditorColors = EditorColors(
    background = Color(0xFFFFFFFF),
    gutter = Color(0xFFFFFFFF),
    gutterText = Color(0xFF237893),
    panel = Color(0xFFF3F3F3),
    panelBorder = Color(0xFFE0E0E0),
    activityBar = Color(0xFF2C2C2C),
    statusBar = Color(0xFF007ACC),
    statusBarText = Color(0xFFFFFFFF),
    tabActive = Color(0xFFFFFFFF),
    tabInactive = Color(0xFFECECEC),
    plainText = Color(0xFF000000),
    syntax = LightSyntaxColors,
    terminalText = Color(0xFF333333),
    terminalPrompt = Color(0xFF007ACC),
    terminalBackground = Color(0xFF000000),
)

/** True-black variant so the AMOLED theme stays consistent inside the editor. */
private val AmoledEditorColors = DarkEditorColors.copy(
    background = Color(0xFF000000),
    gutter = Color(0xFF000000),
    panel = Color(0xFF0A0A0A),
    tabActive = Color(0xFF000000),
    tabInactive = Color(0xFF151515),
    activityBar = Color(0xFF0A0A0A),
)

val LocalEditorColors = staticCompositionLocalOf { DarkEditorColors }

@Composable
@ReadOnlyComposable
fun editorColorsFor(themeMode: ThemeMode, systemInDark: Boolean): EditorColors = when (themeMode) {
    ThemeMode.AMOLED_BLACK -> AmoledEditorColors
    ThemeMode.LIGHT -> LightEditorColors
    ThemeMode.DARK, ThemeMode.HIGH_CONTRAST -> DarkEditorColors
    ThemeMode.SYSTEM_DEFAULT, ThemeMode.DYNAMIC -> if (systemInDark) DarkEditorColors else LightEditorColors
}

/** Convenience for composables that only need the current set. */
val editorColors: EditorColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEditorColors.current

/** Kept so callers can reach Material tokens alongside editor tokens. */
val materialColors
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme
