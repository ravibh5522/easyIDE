package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class ThemeTokensTest {

    private val dark = GraphiteDarkPalette.toTokens()

    @Test fun `overrides replace only the named tokens`() {
        val changed = dark.withOverrides(mapOf(ColorToken.ACCENT to Color.Red))
        assertEquals(Color.Red, changed[ColorToken.ACCENT])
        ColorToken.entries.filter { it != ColorToken.ACCENT }.forEach { assertEquals(it.name, dark[it], changed[it]) }
        assertNotEquals(dark, changed)
        assertEquals(dark, dark.withOverrides(emptyMap()))
    }

    @Test fun `chrome and editor project the same tokens`() {
        val scheme = dark.toColorScheme()
        val editor = dark.toEditorColors()
        assertEquals(dark[ColorToken.ACCENT], scheme.primary)
        assertEquals(editor.accent, scheme.primary)
        assertEquals(editor.background, scheme.background)
        assertEquals(editor.plainText, scheme.onSurface)
        assertEquals(editor.textMuted, scheme.onSurfaceVariant)
        assertEquals(editor.panel, scheme.surfaceContainerLow)
        assertEquals(editor.panelBorder, scheme.outlineVariant)
        assertEquals(dark[ColorToken.ERROR], scheme.error)
    }

    @Test fun `no Material role keeps the baseline purple`() {
        val baseline = androidx.compose.material3.darkColorScheme()
        val scheme = dark.toColorScheme()
        assertNotEquals(baseline.primaryFixed, scheme.primaryFixed)
        assertNotEquals(baseline.secondaryContainer, scheme.secondaryContainer)
        assertNotEquals(baseline.tertiary, scheme.tertiary)
    }

    @Test fun `editor projection carries lanes, git, terminal and decorations`() {
        val editor = dark.toEditorColors()
        assertEquals(GraphiteDarkPalette.lanes, editor.lanes)
        assertEquals(GraphiteDarkPalette.ansi, editor.terminal.ansi)
        assertEquals(GraphiteDarkPalette.signals.gitConflict, editor.git.conflict)
        assertEquals(GraphiteDarkPalette.signals.gitAdded, editor.git.untracked)
        assertEquals(GraphiteDarkPalette.signals.error, editor.decorations.diagnosticError)
        assertEquals(GraphiteDarkPalette.neutrals.editor, editor.terminal.background)
    }

    @Test fun `the accent marks focus everywhere and translucent tints stay translucent`() {
        val a = GraphiteDarkPalette.accent.accent
        listOf(ColorToken.FOCUS_BORDER, ColorToken.TAB_ACTIVE_BORDER, ColorToken.CURSOR, ColorToken.TERMINAL_CURSOR)
            .forEach { assertEquals(it.name, a, dark[it]) }
        assertEquals(GraphiteDarkPalette.emphasis.selection, dark[ColorToken.SELECTION].alpha, ALPHA_TOLERANCE)
        assertEquals(1f, dark[ColorToken.LIST_ACTIVE_SELECTION].alpha, ALPHA_TOLERANCE)
    }

    @Test fun `modes resolve to their palettes`() {
        assertEquals(PaperLightPalette.toTokens(), themeTokensFor(ThemeMode.LIGHT, systemInDark = true))
        assertEquals(GraphiteDarkPalette.toTokens(), themeTokensFor(ThemeMode.DARK, systemInDark = false))
        assertEquals(GraphiteAmoledPalette.toTokens(), themeTokensFor(ThemeMode.AMOLED_BLACK, systemInDark = false))
        assertEquals(HighContrastDarkPalette.toTokens(), themeTokensFor(ThemeMode.HIGH_CONTRAST, systemInDark = true))
        assertEquals(HighContrastLightPalette.toTokens(), themeTokensFor(ThemeMode.HIGH_CONTRAST, systemInDark = false))
        assertEquals(PaperLightPalette.toTokens(), themeTokensFor(ThemeMode.SYSTEM_DEFAULT, systemInDark = false))
        assertEquals(Color(0xFF000000), themeTokensFor(ThemeMode.AMOLED_BLACK, false)[ColorToken.EDITOR_BACKGROUND])
    }

    @Test fun `dynamic takes only the wallpaper accent`() {
        val wallpaper = Accent(Color(0xFFFF8800), Color(0xFF000000))
        val tokens = themeTokensFor(ThemeMode.DYNAMIC, systemInDark = true, dynamicAccent = wallpaper)
        assertEquals(wallpaper.accent, tokens[ColorToken.ACCENT])
        assertEquals(dark[ColorToken.EDITOR_BACKGROUND], tokens[ColorToken.EDITOR_BACKGROUND])
        assertEquals(dark.syntax, tokens.syntax)
        // Other modes ignore a wallpaper accent.
        assertEquals(dark, themeTokensFor(ThemeMode.DARK, systemInDark = true, dynamicAccent = wallpaper))
    }

    @Test fun `window background resources match the editor surfaces`() {
        // Unit tests run with the module directory as working directory.
        assertEquals(PaperLightPalette.neutrals.editor, windowBackground("src/main/res/values/colors.xml"))
        assertEquals(GraphiteDarkPalette.neutrals.editor, windowBackground("src/main/res/values-night/colors.xml"))
    }

    private fun windowBackground(path: String): Color {
        val xml = File(path).readText()
        val hex = Regex("""name="window_background">#([0-9A-Fa-f]{8})<""").find(xml)!!.groupValues[1]
        return Color(hex.toLong(HEX_RADIX))
    }

    private companion object {
        const val ALPHA_TOLERANCE = 0.01f
        const val HEX_RADIX = 16
    }
}
