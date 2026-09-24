package dev.easyide.app.ui.commands

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapTest {

    private val keymap = Keymap.DEFAULT
    private fun ctrl(code: Int, shift: Boolean = false) = KeyChord(code, ctrl = true, shift = shift)

    @Test
    fun matchesExactModifiers() {
        assertEquals(CommandIds.SAVE, keymap.commandFor(ctrl(KeyEvent.KEYCODE_S), terminalFocused = false))
        assertNull(keymap.commandFor(ctrl(KeyEvent.KEYCODE_S, shift = true), terminalFocused = false))
        assertNull(keymap.commandFor(KeyChord(KeyEvent.KEYCODE_S), terminalFocused = false))
        assertEquals(CommandIds.NEXT_EDITOR, keymap.commandFor(ctrl(KeyEvent.KEYCODE_TAB), terminalFocused = false))
        assertEquals(
            CommandIds.PREVIOUS_EDITOR,
            keymap.commandFor(ctrl(KeyEvent.KEYCODE_TAB, shift = true), terminalFocused = false),
        )
    }

    @Test
    fun shellControlChordsPassThroughToAFocusedTerminal() {
        listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D)
            .forEach { assertNull(keymap.commandFor(ctrl(it), terminalFocused = true)) }
        assertNull(keymap.commandFor(ctrl(KeyEvent.KEYCODE_C), terminalFocused = false))
    }

    @Test
    fun globalChordsStillFireInTheTerminal() {
        assertEquals(
            CommandIds.SHOW_COMMANDS,
            keymap.commandFor(ctrl(KeyEvent.KEYCODE_P, shift = true), terminalFocused = true),
        )
        assertEquals(CommandIds.TOGGLE_TERMINAL, keymap.commandFor(ctrl(KeyEvent.KEYCODE_GRAVE), terminalFocused = true))
    }

    @Test
    fun laterBindingWins() {
        val chord = ctrl(KeyEvent.KEYCODE_S)
        val overridden = Keymap(
            listOf(KeyBinding(chord, "a", KeyFocus.OUTSIDE_TERMINAL), KeyBinding(chord, "b", KeyFocus.OUTSIDE_TERMINAL))
        )
        assertEquals("b", overridden.commandFor(chord, terminalFocused = false))
        assertEquals(chord, overridden.chordFor("b"))
    }

    @Test
    fun registryRunsOnlyEnabledCommands() {
        var ran = 0
        val registry = CommandRegistry(
            listOf(Command("on", 0) { ran++ }, Command("off", 0, enabled = false) { ran += 10 })
        )
        assertTrue(registry.execute("on"))
        assertTrue(registry.execute("off"))
        assertFalse(registry.execute("missing"))
        assertEquals(1, ran)
    }
}
