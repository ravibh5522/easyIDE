package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.ui.kit.Tone
import dev.easyide.lsp.protocol.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LspChromeTest {

    @Test fun `a ready server can be restarted or stopped and always shows its log`() {
        assertEquals(
            listOf(ServerAction.RESTART, ServerAction.STOP, ServerAction.SHOW_LOG),
            serverActions(ServerStatusKind.READY, canInstall = false),
        )
    }

    @Test fun `a stopped or memory-paused server can be started`() {
        for (kind in listOf(ServerStatusKind.STOPPED, ServerStatusKind.PAUSED_MEMORY)) {
            assertTrue(ServerAction.START in serverActions(kind, canInstall = false))
        }
    }

    @Test fun `a missing server offers install only when a recipe exists, and always retry`() {
        val without = serverActions(ServerStatusKind.NOT_INSTALLED, canInstall = false)
        val with = serverActions(ServerStatusKind.NOT_INSTALLED, canInstall = true)
        assertFalse(ServerAction.INSTALL in without)
        assertTrue(ServerAction.INSTALL in with)
        assertTrue(ServerAction.RETRY in without && ServerAction.RETRY in with)
        assertFalse(ServerAction.RESTART in with)
    }

    @Test fun `every kind ends with the log action`() {
        for (kind in ServerStatusKind.entries) {
            assertEquals(ServerAction.SHOW_LOG, serverActions(kind, canInstall = true).last())
        }
    }

    @Test fun `severity tones agree with the problems filter and hints stay neutral`() {
        assertEquals(Tone.Danger, DiagnosticSeverity.ERROR.tone())
        assertEquals(Tone.Warning, DiagnosticSeverity.WARNING.tone())
        assertEquals(Tone.Info, DiagnosticSeverity.INFORMATION.tone())
        assertEquals(Tone.Neutral, DiagnosticSeverity.HINT.tone())
    }
}
