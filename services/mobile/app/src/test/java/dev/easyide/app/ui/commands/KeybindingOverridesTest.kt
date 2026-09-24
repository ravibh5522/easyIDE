package dev.easyide.app.ui.commands

import android.view.KeyEvent
import dev.easyide.app.data.settings.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeybindingOverridesTest {

    private fun resolve(text: String) = KeymapResolver.resolve(Keymap.DEFAULT, KeybindingsFile.parse(text), CommandIds.ALL)
    private val ctrlS = KeyChord(KeyEvent.KEYCODE_S, ctrl = true)

    @Test
    fun parsesAndNormalisesChords() {
        assertEquals(KeyChord(KeyEvent.KEYCODE_P, ctrl = true, shift = true), KeyNames.parse("Shift+Ctrl+P"))
        assertEquals(KeyChord(KeyEvent.KEYCODE_GRAVE, ctrl = true), KeyNames.parse("ctrl+`"))
        assertEquals(KeyChord(KeyEvent.KEYCODE_F5, meta = true), KeyNames.parse("cmd+f5"))
        assertEquals("ctrl+shift+alt+meta+tab", KeyNames.format(KeyNames.parse("meta+alt+shift+ctrl+tab")!!))
        assertNull(KeyNames.parse("ctrl+k ctrl+s"))
        assertNull(KeyNames.parse("ctrl+ctrl+s"))
        assertNull(KeyNames.parse("hyper+s"))
        assertNull(KeyNames.parse("ctrl+"))
        assertNull(KeyNames.parse(""))
    }

    @Test
    fun userEntryBeatsDefaultAndFiresOnlyByJson() {
        val r = resolve("[{\"key\": \"ctrl+s\", \"command\": \"workbench.action.files.saveAll\"}, {\"key\": \"f5\", \"command\": \"workbench.action.terminal.new\"}]")
        assertEquals(CommandIds.SAVE_ALL, r.keymap.commandFor(ctrlS, terminalFocused = false))
        assertEquals(CommandIds.NEW_TERMINAL, r.keymap.commandFor(KeyChord(KeyEvent.KEYCODE_F5), terminalFocused = true))
        assertTrue(r.conflicts.any { it.winner.command == CommandIds.SAVE_ALL && it.shadowed.command == CommandIds.SAVE })
        assertTrue(r.diagnostics.any { it.code == DiagnosticCode.CHORD_CONFLICT })
    }

    @Test
    fun removalByCommandKeyAndWhen() {
        assertNull(resolve("[{\"command\": \"-workbench.action.files.save\"}]").keymap.commandFor(ctrlS, false))
        assertNull(resolve("[{\"key\": \"ctrl+s\", \"command\": \"-workbench.action.files.save\"}]").keymap.commandFor(ctrlS, false))
        // Wrong chord: nothing removed.
        assertEquals(CommandIds.SAVE, resolve("[{\"key\": \"ctrl+q\", \"command\": \"-workbench.action.files.save\"}]").keymap.commandFor(ctrlS, false))
        // Matching when (normalised): removed; different when: kept.
        assertNull(resolve("[{\"command\": \"-workbench.action.files.save\", \"when\": \" ! terminalFocus\"}]").keymap.commandFor(ctrlS, false))
        assertEquals(CommandIds.SAVE, resolve("[{\"command\": \"-workbench.action.files.save\", \"when\": \"terminalFocus\"}]").keymap.commandFor(ctrlS, false))
    }

    @Test
    fun whenControlsTerminalFocus() {
        val r = resolve("[{\"key\": \"ctrl+q\", \"command\": \"workbench.action.files.save\", \"when\": \"terminalFocus\"}]")
        val q = KeyChord(KeyEvent.KEYCODE_Q, ctrl = true)
        assertEquals(CommandIds.SAVE, r.keymap.commandFor(q, terminalFocused = true))
        assertNull(r.keymap.commandFor(q, terminalFocused = false))
    }

    @Test
    fun badEntriesAreReportedNotApplied() {
        val r = resolve(
            """[{"key": "ctrl+k ctrl+s", "command": "a"}, {"key": "ctrl+q", "command": "x", "when": "editorFocus"},
                {"command": "x"}, 5, {"key": "ctrl+j", "command": "ext.unknown"}]""",
        )
        val codes = r.diagnostics.map { it.code }.toSet()
        assertTrue(DiagnosticCode.BAD_CHORD in codes)
        assertTrue(DiagnosticCode.UNSUPPORTED_WHEN in codes)
        assertTrue(DiagnosticCode.BAD_ENTRY in codes)
        assertTrue(DiagnosticCode.UNKNOWN_COMMAND in codes)
        // An unknown command still binds: an extension may register it later.
        assertEquals("ext.unknown", r.keymap.commandFor(KeyChord(KeyEvent.KEYCODE_J, ctrl = true), false))
        assertEquals(Keymap.DEFAULT.bindings.size + 1, r.keymap.bindings.size)
        assertEquals(DiagnosticCode.NOT_AN_ARRAY, resolve("{}").diagnostics.single().code)
        assertEquals(DiagnosticCode.PARSE_ERROR, resolve("[").diagnostics.single().code)
    }

    @Test
    fun defaultsHaveNoConflicts() {
        assertTrue(KeymapResolver.conflictsOf(Keymap.DEFAULT.bindings).isEmpty())
        assertTrue(resolve("").diagnostics.isEmpty())
    }
}
