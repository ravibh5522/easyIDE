package dev.easyide.app.ui.commands

import android.view.KeyEvent
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeybindingListTest {

    private fun expr(text: String) = (WhenParser.parse(text) as WhenParseResult.Ok).expr
    private val ctrlR = KeyChord(KeyEvent.KEYCODE_R, ctrl = true)
    private val extPy = KeyBinding(ctrlR, "py.run", KeyFocus.OUTSIDE_TERMINAL, whenExpr = expr("editorLangId == python"))
    private val extGo = KeyBinding(ctrlR, "go.run", KeyFocus.OUTSIDE_TERMINAL, whenExpr = expr("editorLangId == go"))
    private val extAny = KeyBinding(ctrlR, "any.run", KeyFocus.OUTSIDE_TERMINAL)

    private fun build(text: String, ext: List<Pair<KeyBinding, String>> = emptyList()) =
        KeybindingList.build(Keymap.DEFAULT.bindings, ext, KeybindingsFile.parse(text), CommandIds.ALL + ext.map { it.first.command })

    @Test fun `every binding carries its layer and owner`() {
        val r = build("""[{"key": "f5", "command": "workbench.action.terminal.new"}]""", listOf(extPy to "acme.py"))
        assertEquals(Keymap.DEFAULT.bindings.size + 2, r.bindings.size)
        assertEquals(BindingSource.BUILT_IN, r.bindings.first().source)
        val ext = r.bindings.single { it.binding.command == "py.run" }
        assertEquals(BindingSource.EXTENSION to "acme.py", ext.source to ext.owner)
        assertEquals("editorLangId == 'python'", ext.conditionText)
        val user = r.bindings.last()
        assertEquals(BindingSource.USER, user.source)
        assertEquals("f5", user.keyText)
        assertEquals(1, user.entry!!.offset)
    }

    @Test fun `conflicts need the same chord and conditions that can hold together`() {
        assertTrue(build("[]", listOf(extPy to "a", extGo to "b")).conflicts.isEmpty())
        val c = build("[]", listOf(extPy to "a", extAny to "b")).conflicts.single()
        assertEquals("any.run", c.winner.binding.command)
        assertEquals("py.run", c.shadowed.binding.command)
        // A user binding on Ctrl+S shadows the built-in save.
        val save = build("""[{"key": "ctrl+s", "command": "workbench.action.files.saveAll"}]""").conflicts.single()
        assertEquals(CommandIds.SAVE, save.shadowed.binding.command)
    }

    @Test fun `when overlap is false only for contradicting conjunctions`() {
        assertFalse(KeybindingList.mayOverlap(expr("a == x && b"), expr("a == y")))
        assertFalse(KeybindingList.mayOverlap(expr("k"), expr("!k")))
        assertFalse(KeybindingList.mayOverlap(expr("k == x"), expr("k != x")))
        assertTrue(KeybindingList.mayOverlap(expr("k == x || j"), expr("k == y")))
        assertTrue(KeybindingList.mayOverlap(expr("k == x"), expr("j == y")))
        assertTrue(KeybindingList.mayOverlap(null, expr("k")))
    }

    @Test fun `add and remove edit keybindings json keeping the rest`() {
        val added = KeybindingList.add("", "ctrl+k ctrl+s", CommandIds.SAVE_ALL, null)!!
        val parsed = KeybindingsFile.parse(added)
        assertEquals(listOf("ctrl+k ctrl+s" to CommandIds.SAVE_ALL), parsed.entries.map { it.key to it.command })
        val withComment = "[\n  // mine\n  {\"key\": \"f5\", \"command\": \"a\"},\n]\n"
        val two = KeybindingList.add(withComment, "f6", "b", "terminalFocus")!!
        assertTrue(two.contains("// mine"))
        assertEquals(listOf("a", "b"), KeybindingsFile.parse(two).entries.map { it.command })
        assertEquals("terminalFocus", KeybindingsFile.parse(two).entries.last().whenText)
        assertNull(KeybindingList.add("{}", "f5", "a", null))

        // Removing a user binding deletes its entry; removing a built-in appends "-command".
        val r = build(two)
        val f5 = r.bindings.single { it.binding.command == "a" }
        val afterUser = KeybindingList.remove(two, f5)!!
        assertEquals(listOf("b"), KeybindingsFile.parse(afterUser).entries.map { it.command })
        assertTrue(afterUser.contains("// mine"))
        val save = r.bindings.single { it.binding.command == CommandIds.SAVE }
        val afterBuiltIn = KeybindingList.remove(two, save)!!
        val removal = KeybindingsFile.parse(afterBuiltIn).entries.last()
        assertEquals(Triple("ctrl+s", "-" + CommandIds.SAVE, "!terminalFocus"), Triple(removal.key, removal.command, removal.whenText))
        assertNull(build(afterBuiltIn).bindings.firstOrNull { it.binding.command == CommandIds.SAVE })
    }

    @Test fun `removing the only and the last entries leaves valid json`() {
        val one = "[{\"key\": \"f5\", \"command\": \"a\"}]"
        val b = build(one).bindings.last()
        assertEquals(emptyList<KeybindingEntry>(), KeybindingsFile.parse(KeybindingList.remove(one, b)!!).entries)
        val two = "[\n  {\"key\": \"f5\", \"command\": \"a\"},\n  {\"key\": \"f6\", \"command\": \"b\"}\n]"
        val last = build(two).bindings.last()
        val out = KeybindingList.remove(two, last)!!
        assertEquals(listOf("a"), KeybindingsFile.parse(out).entries.map { it.command })
        assertTrue(KeybindingsFile.parse(out).diagnostics.isEmpty())
    }
}
