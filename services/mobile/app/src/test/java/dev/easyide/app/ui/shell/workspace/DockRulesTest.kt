package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.nav.NavPlacement
import dev.easyide.app.ui.shell.nav.NavRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockRulesTest {
    @Test fun `on a phone the dock shows only while the keyboard is up`() {
        assertFalse(DockRules.visible(DockFocus.EDITOR, keyboardUp = false, hardwareKeyboard = false, compact = true))
        assertTrue(DockRules.visible(DockFocus.EDITOR, keyboardUp = true, hardwareKeyboard = false, compact = true))
        assertTrue(DockRules.visible(DockFocus.TERMINAL, keyboardUp = true, hardwareKeyboard = false, compact = true))
    }

    @Test fun `a hardware keyboard needs no dock`() {
        assertFalse(DockRules.visible(DockFocus.EDITOR, keyboardUp = true, hardwareKeyboard = true, compact = true))
        assertFalse(DockRules.visible(DockFocus.TERMINAL, keyboardUp = false, hardwareKeyboard = true, compact = false))
    }

    @Test fun `any other text field gets the OS keyboard only`() {
        assertFalse(DockRules.visible(DockFocus.NONE, keyboardUp = true, hardwareKeyboard = false, compact = true))
        assertFalse(DockRules.visible(DockFocus.NONE, keyboardUp = true, hardwareKeyboard = false, compact = false))
    }

    @Test fun `a wider window keeps the dock with the focused input`() {
        assertTrue(DockRules.visible(DockFocus.EDITOR, keyboardUp = false, hardwareKeyboard = false, compact = false))
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
            val dock = DockRules.visible(DockFocus.EDITOR, keyboardUp, hardwareKeyboard = false, compact = true)
            assertEquals(keyboardUp, dock)
            assertEquals(!keyboardUp, bar)
        }
    }
}
