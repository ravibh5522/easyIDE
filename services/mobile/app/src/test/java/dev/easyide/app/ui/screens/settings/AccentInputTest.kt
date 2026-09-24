package dev.easyide.app.ui.screens.settings

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.theme.AccentDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentInputTest {

    private val darkPanel = Color(0xFF15181D)
    private val darkEditor = Color(0xFF0E1014)
    private val green = Color(0xFF3FB950)
    private val red = Color(0xFFF85149)

    @Test fun `hue distance takes the short way round the circle`() {
        assertEquals(20f, AccentInput.hueDistance(350f, 10f), 0.001f)
        assertEquals(20f, AccentInput.hueDistance(10f, 350f), 0.001f)
        assertEquals(180f, AccentInput.hueDistance(0f, 180f), 0.001f)
        assertEquals(0f, AccentInput.hueDistance(90f, 90f), 0.001f)
    }

    @Test fun `text that is not a colour reports nothing`() {
        val r = AccentInput.report("orange", darkPanel, darkEditor, listOf(green))
        assertNull(r.choice)
        assertNull(r.shown)
        assertFalse(r.adjusted || r.nearSignal)
    }

    @Test fun `theme and wallpaper are choices with no colour to guard`() {
        assertEquals(AccentChoice.Theme, AccentInput.report("theme", darkPanel, darkEditor, emptyList()).choice)
        val wallpaper = AccentInput.report("wallpaper", darkPanel, darkEditor, emptyList())
        assertEquals(AccentChoice.Wallpaper, wallpaper.choice)
        assertNull(wallpaper.shown)
    }

    @Test fun `a readable colour is shown as typed`() {
        val r = AccentInput.report("#FF8A3D", darkPanel, darkEditor, emptyList())
        assertEquals(AccentChoice.Custom(0xFF8A3D), r.choice)
        assertEquals(Color(0xFFFF8A3D), r.shown)
        assertFalse(r.adjusted)
    }

    @Test fun `a colour too dark for the panel is adjusted to one that passes`() {
        val r = AccentInput.report("#101060", darkPanel, darkEditor, emptyList())
        assertTrue(r.adjusted)
        val shown = requireNotNull(r.shown)
        assertTrue(AccentDerivation.contrast(shown, darkPanel) >= AccentDerivation.MIN_ON_PANEL)
        assertTrue(AccentDerivation.contrast(shown, darkEditor) >= AccentDerivation.MIN_ON_EDITOR)
    }

    @Test fun `alpha is dropped before the guard`() {
        assertEquals(AccentInput.report("#FF8A3D", darkPanel, darkEditor, emptyList()).shown, AccentInput.report("#FF8A3D22", darkPanel, darkEditor, emptyList()).shown)
    }

    @Test fun `a hue within twenty degrees of a status colour warns and still applies`() {
        val nearGreen = AccentInput.report("#4CC24F", darkPanel, darkEditor, listOf(green, red))
        assertTrue(nearGreen.nearSignal)
        assertNotNull(nearGreen.shown)
        assertFalse(AccentInput.report("#5CB8FF", darkPanel, darkEditor, listOf(green, red)).nearSignal)
    }

    @Test fun `greys have no hue to compare`() {
        assertFalse(AccentInput.nearSignal(Color(0xFF808080), listOf(green, red)))
        assertFalse(AccentInput.nearSignal(Color(0xFFFF8A3D), listOf(Color(0xFF808080))))
    }

    @Test fun `every swatch is a valid accent id and the ids are distinct`() {
        val ids = AccentInput.SWATCHES.mapNotNull { it.rgb }.map { AccentChoice.Custom(it).id }
        assertEquals(ids.distinct(), ids)
        ids.forEach { assertTrue(it, AccentChoice.parse(it) is AccentChoice.Custom) }
        assertEquals(1, AccentInput.SWATCHES.count { it.rgb == null })
    }
}
