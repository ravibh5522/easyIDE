package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.data.settings.ChromeVisibility.ALWAYS
import dev.easyide.app.data.settings.ChromeVisibility.AUTO
import dev.easyide.app.data.settings.ChromeVisibility.NEVER
import dev.easyide.app.ui.shell.nav.NavPlacement
import dev.easyide.app.ui.shell.nav.NavRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockRulesTest {
    private fun auto(focus: DockFocus, keyboardUp: Boolean, hardware: Boolean, compact: Boolean) = DockRules.visible(AUTO, focus, keyboardUp, hardware, compact)

    @Test fun `on a phone the dock shows only while the keyboard is up`() {
        assertFalse(auto(DockFocus.EDITOR, keyboardUp = false, hardware = false, compact = true))
        assertTrue(auto(DockFocus.EDITOR, keyboardUp = true, hardware = false, compact = true))
        assertTrue(auto(DockFocus.TERMINAL, keyboardUp = true, hardware = false, compact = true))
    }

    @Test fun `a tablet with a hardware keyboard has no row at all`() {
        assertFalse(auto(DockFocus.EDITOR, keyboardUp = true, hardware = true, compact = false))
        assertFalse(auto(DockFocus.EDITOR, keyboardUp = false, hardware = true, compact = false))
        assertFalse(auto(DockFocus.TERMINAL, keyboardUp = false, hardware = true, compact = false))
    }

    @Test fun `a tablet without a keyboard shows the row with the on-screen keyboard only`() {
        assertTrue(auto(DockFocus.EDITOR, keyboardUp = true, hardware = false, compact = false))
        assertFalse(auto(DockFocus.EDITOR, keyboardUp = false, hardware = false, compact = false))
    }

    @Test fun `any other text field gets the OS keyboard only`() {
        assertFalse(auto(DockFocus.NONE, keyboardUp = true, hardware = false, compact = true))
        assertFalse(DockRules.visible(ALWAYS, DockFocus.NONE, keyboardUp = true, hardwareKeyboard = false, compact = false))
    }

    @Test fun `always follows the focused input and never hides it`() {
        assertTrue(DockRules.visible(ALWAYS, DockFocus.EDITOR, keyboardUp = false, hardwareKeyboard = true, compact = false))
        assertFalse(DockRules.visible(NEVER, DockFocus.EDITOR, keyboardUp = true, hardwareKeyboard = false, compact = true))
    }

    @Test fun `the toolbar actions are the editor's and follow their own setting`() {
        val editor = DockRules.show(AUTO, NEVER, DockFocus.EDITOR, keyboardUp = true, hardwareKeyboard = false, compact = true)
        assertTrue(editor.keys)
        assertFalse(editor.actions)
        val terminal = DockRules.show(ALWAYS, ALWAYS, DockFocus.TERMINAL, keyboardUp = false, hardwareKeyboard = false, compact = false)
        assertTrue(terminal.keys)
        assertFalse(terminal.actions)
        assertFalse(DockRules.show(NEVER, NEVER, DockFocus.EDITOR, keyboardUp = true, hardwareKeyboard = false, compact = true).any)
    }

    @Test fun `the bottom bar steps aside for the keyboard and the rail never does`() {
        assertTrue(NavRules.barShown(NavPlacement.BOTTOM, keyboardUp = false))
        assertFalse(NavRules.barShown(NavPlacement.BOTTOM, keyboardUp = true))
        assertTrue(NavRules.barShown(NavPlacement.RAIL_START, keyboardUp = true))
        assertTrue(NavRules.barShown(NavPlacement.RAIL_END, keyboardUp = true))
    }

    @Test fun `on a phone exactly one of the bar and the dock is shown while typing`() {
        listOf(true, false).forEach { keyboardUp ->
            val bar = NavRules.barShown(NavPlacement.BOTTOM, keyboardUp)
            val dock = auto(DockFocus.EDITOR, keyboardUp, hardware = false, compact = true)
            assertEquals(keyboardUp, dock)
            assertEquals(!keyboardUp, bar)
        }
    }
}
