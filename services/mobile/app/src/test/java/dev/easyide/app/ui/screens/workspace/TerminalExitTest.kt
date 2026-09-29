package dev.easyide.app.ui.screens.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExitTest {

    @Test fun `a SIGKILL the app did not send is blamed on the system`() {
        assertTrue(TerminalExit.killedBySystem(-9, killedByApp = false))
    }

    @Test fun `the app's own kills and ordinary exits are not`() {
        assertFalse(TerminalExit.killedBySystem(-9, killedByApp = true))
        assertFalse(TerminalExit.killedBySystem(0, killedByApp = false))
        assertFalse(TerminalExit.killedBySystem(137, killedByApp = false))
        assertFalse(TerminalExit.killedBySystem(-1, killedByApp = false))
    }
}
