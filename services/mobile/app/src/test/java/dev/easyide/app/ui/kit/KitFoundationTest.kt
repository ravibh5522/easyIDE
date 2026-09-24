package dev.easyide.app.ui.kit

import dev.easyide.app.ui.props.HapticsLevel
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.app.ui.theme.toTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitFoundationTest {

    private val dark = GraphiteDarkPalette.toTokens().toEditorColors()
    private val light = PaperLightPalette.toTokens().toEditorColors()

    @Test fun `every tone maps to a colour and the tones are distinguishable`() {
        for (colors in listOf(dark, light)) {
            val content = Tone.entries.map { it.content(colors) }
            assertEquals(Tone.entries.size, content.distinct().size)
            Tone.entries.forEach { assertEquals(1f, it.container(colors).alpha, 0f) }
        }
    }

    @Test fun `accent and danger read from their theme tokens`() {
        assertEquals(dark.accent, Tone.Accent.content(dark))
        assertEquals(dark.error, Tone.Danger.content(dark))
        assertEquals(dark.onAccent, Tone.Accent.onFill(dark))
        assertEquals(dark.background, Tone.Success.onFill(dark))
    }

    @Test fun `a tone wash differs from its surface but stays close to it`() {
        val wash = Tone.Warning.container(dark)
        assertNotEquals(dark.raised, wash)
        assertTrue(kotlin.math.abs(wash.red - dark.raised.red) < 0.2f)
    }

    @Test fun `haptic levels gate events as the table says`() {
        HapticEvent.entries.forEach { assertFalse(it.name, it.allowedAt(HapticsLevel.OFF)) }
        HapticEvent.entries.forEach { assertTrue(it.name, it.allowedAt(HapticsLevel.FULL)) }
        assertFalse(HapticEvent.KeyTap.allowedAt(HapticsLevel.SUBTLE))
        assertFalse(HapticEvent.Snap.allowedAt(HapticsLevel.SUBTLE))
        assertTrue(HapticEvent.Toggle.allowedAt(HapticsLevel.SUBTLE))
        assertTrue(HapticEvent.Reject.allowedAt(HapticsLevel.SUBTLE))
    }

    @Test fun `newer haptic constants fall back on old platforms`() {
        assertNotEquals(HapticEvent.PickUp.constantFor(34), HapticEvent.PickUp.constantFor(29))
        assertEquals(HapticEvent.KeyTap.constantFor(21), HapticEvent.KeyTap.constantFor(34))
    }
}
