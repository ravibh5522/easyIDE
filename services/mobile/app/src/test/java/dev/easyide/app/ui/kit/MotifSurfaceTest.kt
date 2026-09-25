package dev.easyide.app.ui.kit

import dev.easyide.app.ui.props.HapticsLevel
import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotifSurfaceTest {
    @Test fun `every surface shows between one and three motif instances and dialogs none`() {
        MotifSurface.entries.filter { it != MotifSurface.Dialog }.forEach { assertTrue(it.name, it.elements.size in 1..3) }
        assertTrue(MotifSurface.Dialog.elements.isEmpty())
    }

    @Test fun `off keeps only the functional states`() {
        val drawn = MotifSurface.entries.flatMap { s -> MotifElement.entries.filter { s.allows(it, Motif.OFF) } }.toSet()
        assertEquals(setOf(MotifElement.CursorState, MotifElement.CellFill), drawn)
    }

    @Test fun `art and ticks need the full level`() {
        assertFalse(MotifSurface.EmptyState.allows(MotifElement.Art, Motif.SUBTLE))
        assertTrue(MotifSurface.EmptyState.allows(MotifElement.Art, Motif.FULL))
        assertFalse(MotifSurface.Onboarding.allows(MotifElement.Ticks, Motif.SUBTLE))
        assertTrue(MotifSurface.Onboarding.allows(MotifElement.Ticks, Motif.FULL))
    }

    @Test fun `a surface never draws an element it does not list`() {
        assertFalse(MotifSurface.Settings.allows(MotifElement.CropCorners, Motif.FULL))
        assertFalse(MotifSurface.Dialog.allows(MotifElement.CursorState, Motif.FULL))
        assertFalse(MotifSurface.Workspace.allows(MotifElement.PromptGlyph, Motif.FULL))
    }

    @Test fun `only Home and empty-state headers have a cycle cap`() {
        val capped = MotifSurface.entries.filter { it.cursorCycles != null }
        assertEquals(setOf(MotifSurface.Home, MotifSurface.EmptyState), capped.toSet())
        capped.forEach { assertEquals(Motion.HEADER_BLINK_CYCLES, it.cursorCycles) }
        assertNull(MotifSurface.Loading.cursorCycles)
    }

    @Test fun `success confirms and danger rejects, both at the subtle level`() {
        assertEquals(HapticEvent.Success, Tone.Success.hapticEvent())
        assertEquals(HapticEvent.Reject, Tone.Danger.hapticEvent())
        listOf(Tone.Neutral, Tone.Accent, Tone.Warning, Tone.Info).forEach { assertNull(it.name, it.hapticEvent()) }
        listOf(Tone.Success, Tone.Danger).forEach {
            assertTrue(it.hapticEvent()!!.allowedAt(HapticsLevel.SUBTLE))
            assertFalse(it.hapticEvent()!!.allowedAt(HapticsLevel.OFF))
        }
    }
}
