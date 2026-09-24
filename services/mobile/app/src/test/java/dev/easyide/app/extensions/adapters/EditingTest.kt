package dev.easyide.app.extensions.adapters

import android.view.KeyEvent
import dev.easyide.app.extensions.host.TextEdits
import dev.easyide.extensions.action.TextPosition
import dev.easyide.extensions.action.TextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditingTest {

    private fun r(l1: Int, c1: Int, l2: Int, c2: Int) = TextRange(TextPosition(l1, c1), TextPosition(l2, c2))

    @Test fun `text edits apply from the end and refuse overlaps and bad ranges`() {
        val text = "hello\nworld"
        assertEquals("HEllo\nwoRLD", TextEdits.apply(text, listOf(r(0, 0, 0, 2) to "HE", r(1, 2, 1, 5) to "RLD")))
        assertEquals("hello!\nworld", TextEdits.apply(text, listOf(r(0, 99, 0, 99) to "!")))
        assertNull(TextEdits.apply(text, listOf(r(0, 0, 0, 3) to "a", r(0, 2, 0, 4) to "b")))
        assertNull(TextEdits.apply(text, listOf(r(5, 0, 5, 0) to "x")))
    }

    @Test fun `workspace edits yield text edits and refuse resource operations`() {
        val edit = Json.parseToJsonElement("""
            { "changes": { "file:///workspace/a.txt": [ { "range": { "start": {"line":0,"character":0}, "end": {"line":0,"character":1} }, "newText": "X" } ] },
              "documentChanges": [ { "textDocument": { "uri": "file:///workspace/b.txt", "version": 1 }, "edits": [] } ] }
        """) as JsonObject
        val edits = TextEdits.fromWorkspaceEdit(edit)!!
        assertEquals(listOf("/workspace/a.txt"), edits.map { it.path })
        val create = Json.parseToJsonElement("""{ "documentChanges": [ { "kind": "create", "uri": "file:///workspace/c" } ] }""") as JsonObject
        assertNull(TextEdits.fromWorkspaceEdit(create))
    }

    @Test fun `line and column are 1-based`() {
        assertEquals(1 to 1, TextEdits.lineColumn("ab\ncd", 0))
        assertEquals(2 to 2, TextEdits.lineColumn("ab\ncd", 4))
    }

    @Test fun `editor key row keys edit and move the caret`() {
        assertEquals(EditorEdit("ab", 1, 1), EditorKeys.apply("ab", 2, 2, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(EditorEdit("a", 1, 1), EditorKeys.apply("ab", 2, 2, KeyEvent.KEYCODE_DEL))
        assertEquals(EditorEdit("  x\n  ", 6, 6), EditorKeys.apply("  x", 3, 3, KeyEvent.KEYCODE_ENTER))
        assertEquals(EditorEdit("abc\nd", 5, 5), EditorKeys.apply("abc\nd", 2, 2, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(EditorEdit("abc\nd", 1, 1), EditorKeys.apply("abc\nd", 5, 5, KeyEvent.KEYCODE_DPAD_UP))
        assertNull(EditorKeys.apply("ab", 0, 0, KeyEvent.KEYCODE_A))
    }

    @Test fun `word and line lookups`() {
        assertEquals("foo_1", EditorText.wordAt("x = foo_1(y)", 6))
        assertEquals("  b", EditorText.lineAt("a\n  b\nc", 3))
        assertEquals("  ", EditorText.indentAt("a\n  b\nc", 3))
    }
}
