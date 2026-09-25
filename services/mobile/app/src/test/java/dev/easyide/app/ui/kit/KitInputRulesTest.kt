package dev.easyide.app.ui.kit

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.AccentDerivation
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.HighContrastDarkPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.app.ui.theme.toTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitInputRulesTest {

    private val palettes = listOf(GraphiteDarkPalette, PaperLightPalette, HighContrastDarkPalette).map { it.toTokens().toEditorColors() }

    private fun forEachPalette(check: (EditorColors) -> Unit) = palettes.forEach(check)

    // --- field ---

    @Test fun `field edge is disabled over error over focus over idle`() {
        assertEquals(FieldEdge.Disabled, fieldEdge(enabled = false, focused = true, error = "bad"))
        assertEquals(FieldEdge.Error, fieldEdge(enabled = true, focused = true, error = "bad"))
        assertEquals(FieldEdge.Focused, fieldEdge(enabled = true, focused = true, error = null))
        assertEquals(FieldEdge.Idle, fieldEdge(enabled = true, focused = false, error = null))
    }

    @Test fun `an empty error string is no error`() {
        assertEquals(FieldEdge.Idle, fieldEdge(enabled = true, focused = false, error = ""))
    }

    @Test fun `focus and error draw the ring, idle and disabled the hairline`() {
        assertTrue(FieldEdge.Focused.isRing())
        assertTrue(FieldEdge.Error.isRing())
        assertFalse(FieldEdge.Idle.isRing())
        assertFalse(FieldEdge.Disabled.isRing())
        forEachPalette { c ->
            assertEquals(c.focus, FieldEdge.Focused.color(c))
            assertEquals(c.error, FieldEdge.Error.color(c))
            assertEquals(c.panelBorder, FieldEdge.Idle.color(c))
        }
    }

    @Test fun `clear button needs text, an editable single line`() {
        assertTrue(fieldShowsClear("a", enabled = true, readOnly = false, singleLine = true))
        assertFalse(fieldShowsClear("", enabled = true, readOnly = false, singleLine = true))
        assertFalse(fieldShowsClear("a", enabled = false, readOnly = false, singleLine = true))
        assertFalse(fieldShowsClear("a", enabled = true, readOnly = true, singleLine = true))
        assertFalse(fieldShowsClear("a", enabled = true, readOnly = false, singleLine = false))
    }

    // --- button ---

    @Test fun `only the primary button is a filled accent and danger is never a fill`() = forEachPalette { c ->
        val paints = KitButtonStyle.entries.associateWith { buttonPaint(it, true, c) }
        assertEquals(c.accent, paints.getValue(KitButtonStyle.Primary).fill)
        assertEquals(c.onAccent, paints.getValue(KitButtonStyle.Primary).content)
        assertEquals(listOf(KitButtonStyle.Primary), paints.filterValues { it.fill != null }.keys.toList())
        assertEquals(c.error, paints.getValue(KitButtonStyle.Danger).content)
        assertEquals(c.panelBorder, paints.getValue(KitButtonStyle.Secondary).border)
        assertNull(paints.getValue(KitButtonStyle.Ghost).border)
    }

    @Test fun `a disabled button of every style uses the disabled text colour`() = forEachPalette { c ->
        KitButtonStyle.entries.forEach { assertEquals(c.textDisabled, buttonPaint(it, false, c).content) }
        assertEquals(c.raised, buttonPaint(KitButtonStyle.Primary, false, c).fill)
    }

    @Test fun `button labels are readable on their surface`() = forEachPalette { c ->
        for (style in listOf(KitButtonStyle.Primary, KitButtonStyle.Secondary, KitButtonStyle.Ghost)) {
            val p = buttonPaint(style, true, c)
            for (surface in listOf(c.panel, c.raised, c.overlay).let { if (p.fill != null) listOf(p.fill) else it }) {
                val ratio = AccentDerivation.contrast(p.content, surface!!)
                assertTrue("$style $ratio", ratio >= AA)
            }
        }
    }

    @Test fun `the loading sweep visits every cell then wraps`() {
        var cell = 0
        val seen = List(8) { cell.also { cell = nextSweepCell(cell) } }
        assertEquals((0..7).toList(), seen)
        assertEquals(0, cell)
    }

    // --- tag ---

    @Test fun `a resting tag is a wash and a selected tag fills with the tone`() = forEachPalette { c ->
        for (tone in Tone.entries) {
            val rest = tagPaint(tone, false, c)
            val on = tagPaint(tone, true, c)
            assertEquals(tone.content(c), rest.content)
            assertEquals(tone.container(c), rest.fill)
            assertEquals(tone.content(c), on.fill)
            assertEquals(tone.onFill(c), on.content)
        }
    }

    @Test fun `a neutral tag keeps the chrome hairline, other tones outline in the tone`() = forEachPalette { c ->
        assertEquals(c.panelBorder, tagPaint(Tone.Neutral, false, c).border)
        assertEquals(c.error, tagPaint(Tone.Danger, false, c).border)
    }

    @Test fun `tag text is readable on its wash and on its fill`() = forEachPalette { c ->
        for (tone in Tone.entries) {
            for (selected in listOf(false, true)) {
                val p = tagPaint(tone, selected, c)
                val ratio = AccentDerivation.contrast(p.content, p.fill)
                assertTrue("$tone selected=$selected $ratio", ratio >= LARGE_OR_MARK)
            }
        }
    }

    // --- toggle ---

    @Test fun `checked switch and check are accent fills, radio is an accent ring`() = forEachPalette { c ->
        for (kind in listOf(ToggleKind.Switch, ToggleKind.Check)) {
            val p = togglePaint(kind, true, true, c)
            assertEquals(c.accent, p.fill)
            assertEquals(c.onAccent, p.mark)
        }
        val radio = togglePaint(ToggleKind.Radio, true, true, c)
        assertEquals(Color.Transparent, radio.fill)
        assertEquals(c.accent, radio.border)
        assertEquals(c.accent, radio.mark)
    }

    @Test fun `unchecked and disabled toggles are outlines with no accent`() = forEachPalette { c ->
        for (kind in ToggleKind.entries) {
            val off = togglePaint(kind, false, true, c)
            assertEquals(Color.Transparent, off.fill)
            assertEquals(c.textMuted, off.border)
            val disabled = togglePaint(kind, true, false, c)
            assertEquals(c.textDisabled, disabled.border)
            assertEquals(Color.Transparent, disabled.fill)
        }
    }

    @Test fun `toggle outline meets the 3 to 1 mark contrast on the panel`() = forEachPalette { c ->
        for (kind in ToggleKind.entries) {
            val off = togglePaint(kind, false, true, c)
            assertTrue(AccentDerivation.contrast(off.border, c.panel) >= LARGE_OR_MARK)
        }
    }

    // --- state layer ---

    @Test fun `press outranks hover and rest draws nothing`() {
        assertEquals(KitStateLayer.PRESSED, KitStateLayer.alphaFor(pressed = true, hovered = true), 0f)
        assertEquals(KitStateLayer.HOVER, KitStateLayer.alphaFor(pressed = false, hovered = true), 0f)
        assertEquals(0f, KitStateLayer.alphaFor(pressed = false, hovered = false), 0f)
        assertTrue(KitStateLayer.PRESSED > KitStateLayer.HOVER)
    }

    private companion object {
        const val AA = 4.5
        const val LARGE_OR_MARK = 3.0
    }
}
