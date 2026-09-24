package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.AccentDerivation.withAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentDerivationTest {

    private val dark = GraphiteDarkPalette.toTokens()
    private val light = PaperLightPalette.toTokens()

    private fun hue(degrees: Float, l: Float = 0.5f) = Hsl(degrees, 1f, l).toColor()

    @Test fun `two hundred hues stay readable on both surfaces and under their label`() {
        for (tokens in listOf(dark, light)) {
            for (i in 0 until 200) {
                val t = tokens.withAccent(hue(i * 1.8f, l = 0.15f + (i % 7) * 0.12f))
                val accent = t[ColorToken.ACCENT]
                val where = "hue ${i * 1.8f} dark=${tokens.isDark}"
                assertTrue("$where on panel", AccentDerivation.contrast(accent, t[ColorToken.PANEL]) >= AccentDerivation.MIN_ON_PANEL)
                assertTrue("$where on editor", AccentDerivation.contrast(accent, t[ColorToken.EDITOR_BACKGROUND]) >= AccentDerivation.MIN_ON_EDITOR)
                assertTrue("$where label", AccentDerivation.contrast(accent, t[ColorToken.ON_ACCENT]) >= AccentDerivation.MIN_ON_PANEL)
            }
        }
    }

    @Test fun `a readable accent is kept as chosen`() {
        val orange = Color(0xFFFF8A3D)
        assertEquals(orange, AccentDerivation.guard(orange, dark[ColorToken.PANEL], dark[ColorToken.EDITOR_BACKGROUND]))
    }

    @Test fun `an unreadable accent is nudged toward the readable side and keeps its hue`() {
        val darkBlue = Color(0xFF101060)
        val on = AccentDerivation.guard(darkBlue, dark[ColorToken.PANEL], dark[ColorToken.EDITOR_BACKGROUND])
        assertTrue(on.red + on.green + on.blue > darkBlue.red + darkBlue.green + darkBlue.blue)
        assertEquals(Hsl.of(darkBlue).h, Hsl.of(on).h, 3f)
        val paleYellow = Color(0xFFFFFFA0)
        val off = AccentDerivation.guard(paleYellow, light[ColorToken.PANEL], light[ColorToken.EDITOR_BACKGROUND])
        assertTrue(off.red + off.green + off.blue < paleYellow.red + paleYellow.green + paleYellow.blue)
    }

    @Test fun `the whole accent family follows and neutrals, syntax and terminal colours do not`() {
        val chosen = Color(0xFF3CC9C0)
        val t = dark.withAccent(chosen)
        val accent = t[ColorToken.ACCENT]
        listOf(ColorToken.FOCUS_BORDER, ColorToken.TAB_ACTIVE_BORDER, ColorToken.ACTIVITY_BAR_ACTIVE_BORDER, ColorToken.CURSOR, ColorToken.TERMINAL_CURSOR)
            .forEach { assertEquals(it.name, accent, t[it]) }
        assertEquals(dark[ColorToken.SELECTION].alpha, t[ColorToken.SELECTION].alpha, 0f)
        listOf(ColorToken.EDITOR_BACKGROUND, ColorToken.PANEL, ColorToken.FOREGROUND, ColorToken.ERROR, ColorToken.ANSI_RED, ColorToken.GIT_ADDED)
            .forEach { assertEquals(it.name, dark[it], t[it]) }
        assertEquals(dark.syntax, t.syntax)
    }

    @Test fun `contrast maths matches the reference and the label picks the better side`() {
        assertEquals(21.0, AccentDerivation.contrast(Color.White, Color.Black), 0.01)
        assertEquals(Color(0xFF0E1014), AccentDerivation.onAccentFor(Color(0xFFFF8A3D)))
        assertEquals(Color.White, AccentDerivation.onAccentFor(Color(0xFF1A3A8A)))
    }

    @Test fun `hsl round trips`() {
        listOf(Color(0xFFFF8A3D), Color(0xFF3CC9C0), Color(0xFF808080), Color(0xFF101060)).forEach {
            val back = Hsl.of(it).toColor()
            assertEquals(it.red, back.red, 0.01f); assertEquals(it.green, back.green, 0.01f); assertEquals(it.blue, back.blue, 0.01f)
        }
    }
}
