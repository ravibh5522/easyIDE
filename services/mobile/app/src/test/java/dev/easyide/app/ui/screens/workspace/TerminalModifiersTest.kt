package dev.easyide.app.ui.screens.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModifiersTest {

    @After fun reset() = TerminalModifiers.consume()

    @Test fun `ctrl turns a letter into its control byte`() {
        TerminalModifiers.toggle(TerminalModifiers.CTRL_ID)
        assertEquals("\u0003", TerminalModifiers.wrap("c"))
        assertFalse(TerminalModifiers.ctrl)
    }

    @Test fun `a modifier applies to one key only`() {
        TerminalModifiers.toggle(TerminalModifiers.CTRL_ID)
        assertEquals("\u0012", TerminalModifiers.wrap("r"))
        assertEquals("r", TerminalModifiers.wrap("r"))
    }

    @Test fun `alt prefixes escape and combines with ctrl`() {
        TerminalModifiers.toggle(TerminalModifiers.ALT_ID)
        assertEquals("\u001bb", TerminalModifiers.wrap("b"))
        TerminalModifiers.toggle(TerminalModifiers.ALT_ID)
        TerminalModifiers.toggle(TerminalModifiers.CTRL_ID)
        assertEquals("\u001b\u0002", TerminalModifiers.wrap("b"))
    }

    @Test fun `an escape sequence is passed through and still disarms`() {
        TerminalModifiers.toggle(TerminalModifiers.CTRL_ID)
        assertEquals("\u001b[A", TerminalModifiers.wrap("\u001b[A"))
        assertFalse(TerminalModifiers.ctrl)
    }

    @Test fun `tapping a modifier twice disarms it and other ids are not modifiers`() {
        assertTrue(TerminalModifiers.toggle(TerminalModifiers.ALT_ID))
        assertTrue(TerminalModifiers.alt)
        TerminalModifiers.toggle(TerminalModifiers.ALT_ID)
        assertFalse(TerminalModifiers.alt)
        assertFalse(TerminalModifiers.toggle("other.command"))
    }

    @Test fun `control byte table matches the terminal's`() {
        assertEquals('\u0000', TerminalModifiers.controlOf(' '))
        assertEquals('\u001b', TerminalModifiers.controlOf('['))
        assertEquals('\u001f', TerminalModifiers.controlOf('/'))
        assertEquals('1', TerminalModifiers.controlOf('1'))
    }
}
