package dev.easyide.app.extensions.adapters

import android.view.KeyEvent
import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeyChord
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.screens.workspace.TerminalKeyboard
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAndKeysTest {

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "commands": [
            { "command": "demo.a", "title": "Alpha", "enablement": "gitRepo" },
            { "command": "demo.b", "title": "Beta" },
            { "command": "demo.c", "title": "Gamma" },
            { "command": "demo.hidden", "title": "Hidden" }
          ],
          "menus": {
            "editor/title": [
              { "command": "demo.c" },
              { "command": "demo.b", "group": "z@1" },
              { "command": "demo.a", "group": "navigation@2", "when": "editorLangId == python" },
              { "command": "workbench.action.files.save", "group": "navigation" }
            ],
            "commandPalette": [ { "command": "demo.hidden", "when": "false" } ],
            "keyRow": [ { "command": "demo.b" } ]
          },
          "keybindings": [
            { "command": "demo.b", "key": "ctrl+shift+r", "when": "editorLangId == python" },
            { "command": "demo.c", "key": "ctrl+k ctrl+s ctrl+x" },
            { "command": "demo.b", "key": "ctrl+k ctrl+b", "when": "editorLangId == python" },
            { "command": "demo.a", "key": "ctrl+t", "linux": "alt+f5", "when": "terminalFocus" }
          ]
        },
        "easyide": { "keyRows": [
          { "id": "demo.py", "title": "Py", "when": "editorLangId == python", "keys": [ { "label": ":", "insert": ":" } ] },
          { "id": "demo.term", "title": "T", "when": "terminalFocus", "keys": [ { "label": "ls", "insert": "ls" } ] }
        ] }
    """))
    private val snapshot = ExtFixtures.snapshot(pack, builtIn = Contributions(keyRows = listOf(TerminalKeyboard.row("Terminal"))))

    @Test fun `menu entries filter by when, drop unknown commands and sort navigation first`() {
        val py = ExtFixtures.context("editorLangId" to "\"python\"")
        assertEquals(listOf("demo.a", "demo.b", "demo.c"), MenuModel.items("editor/title", snapshot, py, emptySet()).map { it.command.command })
        assertEquals(listOf("demo.b", "demo.c"), MenuModel.items("editor/title", snapshot, ExtFixtures.context(), emptySet()).map { it.command.command })
    }

    @Test fun `enablement false keeps the entry but disabled, and hidden refs disappear`() {
        val py = ExtFixtures.context("editorLangId" to "\"python\"")
        assertFalse(MenuModel.items("editor/title", snapshot, py, emptySet()).first().enabled)
        val hidden = setOf("menu:editor/title:demo.b")
        assertEquals(listOf("demo.a", "demo.c"), MenuModel.items("editor/title", snapshot, py, hidden).map { it.command.command })
    }

    @Test fun `commandPalette when false hides a command from the palette`() {
        assertFalse(MenuModel.inPalette("demo.hidden", snapshot, ExtFixtures.context(), emptySet()))
        assertTrue(MenuModel.inPalette("demo.b", snapshot, ExtFixtures.context(), emptySet()))
    }

    @Test fun `key names parse VS Code chords`() {
        assertEquals(KeyChord(KeyEvent.KEYCODE_R, ctrl = true, shift = true), KeyNames.parse("Ctrl+Shift+R"))
        assertEquals(KeyChord(KeyEvent.KEYCODE_S, meta = true), KeyNames.parse("cmd+s"))
        assertEquals(KeyChord(KeyEvent.KEYCODE_F5), KeyNames.parse("f5"))
        assertEquals(KeyChord(KeyEvent.KEYCODE_DPAD_UP, alt = true), KeyNames.parse("alt+up"))
        assertNull(KeyNames.parse("ctrl+k ctrl+s"))
        assertNull(KeyNames.parse("hyper+x"))
        assertNull(KeyNames.parse("ctrl+ctrl+x"))
    }

    @Test fun `contributed keybindings join the keymap with when-clauses and linux keys`() {
        val skipped = ArrayList<String>()
        val layer = ContributedKeybindings.bindings(snapshot.keybindings) { o, _ -> skipped += o.value.command }
        assertEquals(listOf("demo.c"), skipped)
        val keymap = Keymap.DEFAULT + layer
        val chord = KeyChord(KeyEvent.KEYCODE_R, ctrl = true, shift = true)
        assertEquals("demo.b", keymap.commandFor(chord, false, ExtFixtures.context("editorLangId" to "\"python\"")))
        assertNull(keymap.commandFor(chord, false, ExtFixtures.context("editorLangId" to "\"go\"")))
        val altF5 = KeyChord(KeyEvent.KEYCODE_F5, alt = true)
        // Mentions terminalFocus, so it may fire while the terminal has focus.
        assertEquals("demo.a", keymap.commandFor(altF5, true, ExtFixtures.context("terminalFocus" to "true")))
        // Without terminalFocus in its when, a pack chord never reaches a focused terminal.
        assertNull(keymap.commandFor(chord, true, ExtFixtures.context("editorLangId" to "\"python\"")))
        // A two-press contribution completes only after its prefix, and only where its when holds.
        val k = KeyChord(KeyEvent.KEYCODE_K, ctrl = true)
        val b = KeyChord(KeyEvent.KEYCODE_B, ctrl = true)
        val py = ExtFixtures.context("editorLangId" to "\"python\"")
        assertEquals("demo.b", keymap.commandFor(b, false, py, prefix = k))
        assertNull(keymap.commandFor(b, false, ExtFixtures.context("editorLangId" to "\"go\""), prefix = k))
        assertEquals(CommandIds.TOGGLE_EXPLORER, keymap.commandFor(b, false, py))
    }

    @Test fun `key rows pick per surface and append keyRow menu commands`() {
        val py = ExtFixtures.context("editorLangId" to "\"python\"", "terminalFocus" to "false")
        val editor = KeyRows.active(KeySurface.EDITOR, snapshot, "auto", "auto", py, emptySet())!!
        assertEquals("demo.py", editor.id)
        assertEquals(KeyAction.Command("demo.b"), editor.keys.last().action)
        val term = ExtFixtures.context("editorLangId" to "\"python\"", "terminalFocus" to "true")
        assertEquals("demo.term", KeyRows.active(KeySurface.TERMINAL, snapshot, "auto", "auto", term, emptySet())!!.id)
        // The python row never replaces the shell row; hiding the pack's row falls back to built-in.
        assertEquals(KeyRows.BUILTIN_TERMINAL, KeyRows.active(KeySurface.TERMINAL, snapshot, "auto", "auto", term, setOf("keyRow:demo.term"))!!.id)
        assertNull(KeyRows.active(KeySurface.EDITOR, snapshot, "auto", "auto", ExtFixtures.context(), emptySet()))
        assertEquals("demo.term", KeyRows.active(KeySurface.EDITOR, snapshot, "demo.term", "auto", py, emptySet())!!.id)
    }
}
