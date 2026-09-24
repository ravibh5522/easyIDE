package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast for every built-in palette (decision 0019).
 *
 * Text pairs must reach AA (4.5:1) in the regular palettes and AAA (7:1) in the
 * high-contrast ones; non-text marks (cursor, lanes, squiggles, focus) must reach
 * 3:1, WCAG's threshold for graphical objects. Translucent tints are composited
 * over the surface they paint on, because that is what the reader sees.
 * Failures are collected so one run lists every pair that needs fixing.
 */
class BuiltInPaletteContrastTest {

    private val palettes = mapOf(
        "dark" to GraphiteDarkPalette,
        "amoled" to GraphiteAmoledPalette,
        "light" to PaperLightPalette,
        "hc-dark" to HighContrastDarkPalette,
        "hc-light" to HighContrastLightPalette,
    )

    @Test fun `every text and mark pair meets its WCAG threshold`() {
        val failures = palettes.flatMap { (name, palette) -> failuresFor(name, palette) }
        assertTrue("contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `contrast maths matches the WCAG reference values`() {
        assertClose(21.0, contrast(Color.White, Color.Black))
        assertClose(1.0, contrast(GREY, GREY))
        // #777777 on white is the textbook just-below-AA grey: 4.48:1.
        assertClose(4.48, contrast(Color(0xFF777777), Color.White))
    }

    private fun failuresFor(name: String, palette: Palette): List<String> {
        val t = palette.toTokens()
        val text = if (palette.isHighContrast) AAA else AA
        val out = mutableListOf<String>()
        fun check(label: String, fg: Color, bg: Color, min: Double) {
            val ratio = contrast(fg, bg)
            if (ratio < min) out += "$name $label: ${"%.2f".format(ratio)} < $min"
        }
        fun on(tint: ColorToken, surface: ColorToken) = t[tint].compositeOver(t[surface])

        val editor = t[ColorToken.EDITOR_BACKGROUND]
        val currentLine = on(ColorToken.CURRENT_LINE, ColorToken.EDITOR_BACKGROUND)
        for (surface in listOf(ColorToken.EDITOR_BACKGROUND, ColorToken.PANEL, ColorToken.RAISED, ColorToken.OVERLAY)) {
            check("text/$surface", t[ColorToken.FOREGROUND], t[surface], text)
            check("muted/$surface", t[ColorToken.TEXT_MUTED], t[surface], text)
            check("accent/$surface", t[ColorToken.ACCENT], t[surface], AA)
        }
        check("line number", t[ColorToken.LINE_NUMBER], editor, text)
        check("active line number", t[ColorToken.LINE_NUMBER_ACTIVE], currentLine, text)
        check("status bar", t[ColorToken.STATUS_BAR_FOREGROUND], t[ColorToken.STATUS_BAR], text)
        check("inactive tab", t[ColorToken.TAB_INACTIVE_FOREGROUND], t[ColorToken.TAB_INACTIVE], text)
        check("active tab", t[ColorToken.TAB_ACTIVE_FOREGROUND], t[ColorToken.TAB_ACTIVE], text)
        check("rail icon", t[ColorToken.ACTIVITY_BAR_INACTIVE_FOREGROUND], t[ColorToken.ACTIVITY_BAR], text)
        check("on accent", t[ColorToken.ON_ACCENT], t[ColorToken.ACCENT], text)
        check("selected text", t[ColorToken.EDITOR_FOREGROUND], on(ColorToken.SELECTION, ColorToken.EDITOR_BACKGROUND), text)
        check("tree selection", t[ColorToken.LIST_ACTIVE_SELECTION_FOREGROUND], t[ColorToken.LIST_ACTIVE_SELECTION], text)
        check("search match", t[ColorToken.EDITOR_FOREGROUND], on(ColorToken.SEARCH_MATCH, ColorToken.EDITOR_BACKGROUND), text)
        check(
            "current search match", t[ColorToken.EDITOR_FOREGROUND],
            on(ColorToken.SEARCH_MATCH_CURRENT, ColorToken.EDITOR_BACKGROUND), AA,
        )
        check("inlay hint", t[ColorToken.INLAY_HINT_FOREGROUND], on(ColorToken.INLAY_HINT_BACKGROUND, ColorToken.EDITOR_BACKGROUND), text)

        SyntaxRole.entries.forEach { role ->
            check("syntax $role", t.syntax[role], editor, text)
            check("syntax $role on current line", t.syntax[role], currentLine, text)
        }
        listOf(
            ColorToken.ERROR, ColorToken.WARNING, ColorToken.INFO, ColorToken.SUCCESS,
            ColorToken.GIT_ADDED, ColorToken.GIT_MODIFIED, ColorToken.GIT_DELETED,
            ColorToken.GIT_CONFLICT, ColorToken.GIT_UNTRACKED,
        ).forEach { check("$it/panel", t[it], t[ColorToken.PANEL], text) }

        ColorToken.LANES.forEach { check("$it/panel", t[it], t[ColorToken.PANEL], GRAPHIC) }
        check("cursor", t[ColorToken.CURSOR], editor, GRAPHIC)
        check("cursor on current line", t[ColorToken.CURSOR], currentLine, GRAPHIC)
        check("focus border", t[ColorToken.FOCUS_BORDER], t[ColorToken.RAISED], GRAPHIC)
        check("tab accent bar", t[ColorToken.TAB_ACTIVE_BORDER], t[ColorToken.TAB_ACTIVE], GRAPHIC)
        listOf(ColorToken.DIAGNOSTIC_ERROR, ColorToken.DIAGNOSTIC_WARNING, ColorToken.DIAGNOSTIC_INFORMATION)
            .forEach { check("$it squiggle", t[it], editor, GRAPHIC) }
        if (palette.isHighContrast) check("hairline", t[ColorToken.HAIRLINE], t[ColorToken.PANEL], GRAPHIC)

        val terminal = t[ColorToken.TERMINAL_BACKGROUND]
        check("terminal text", t[ColorToken.TERMINAL_FOREGROUND], terminal, text)
        check("terminal cursor", t[ColorToken.TERMINAL_CURSOR], terminal, GRAPHIC)
        ColorToken.ANSI.forEachIndexed { index, token ->
            // Black and white are background/foreground slots by convention, and
            // bright black is the dim-text colour: graphic threshold only.
            when (index) {
                ANSI_BLACK, ANSI_WHITE, ANSI_BRIGHT_WHITE -> Unit
                ANSI_BRIGHT_BLACK -> check("ansi $index", t[token], terminal, GRAPHIC)
                else -> check("ansi $index", t[token], terminal, text)
            }
        }
        return out
    }

    private val Palette.isHighContrast get() = this == HighContrastDarkPalette || this == HighContrastLightPalette

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue("expected $expected, got $actual", kotlin.math.abs(expected - actual) < 0.01)

    private companion object {
        const val AA = 4.5
        const val AAA = 7.0
        const val GRAPHIC = 3.0
        const val ANSI_BLACK = 0
        const val ANSI_WHITE = 7
        const val ANSI_BRIGHT_BLACK = 8
        const val ANSI_BRIGHT_WHITE = 15
        val GREY = Color(0xFF808080)

        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** WCAG relative luminance of an opaque sRGB colour. */
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double =
                if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
    }
}
