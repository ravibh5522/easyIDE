package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.ui.screens.workspace.edit.TextState
import dev.easyide.lsp.protocol.CompletionItem
import dev.easyide.lsp.session.ServerKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionModelTest {

    private val a = ServerKey("e", "p", "a")
    private val b = ServerKey("e", "p", "b")

    private fun item(json: String): CompletionItem = requireNotNull(CompletionItem.fromJson(Json.parseToJsonElement(json)))
    private fun entry(json: String, server: ServerKey = a) = CompletionEntry(server, item(json))
    private fun accept(e: CompletionEntry, origin: CompletionOrigin, text: String, caret: Int, mode: AcceptMode = AcceptMode.INSERT) =
        requireNotNull(CompletionModel.accept(e, origin, text, caret, mode, indent = "", tab = "    ", variable = { null }))

    @Test
    fun wordStartStopsAtNonIdentifiers() {
        assertEquals(4, CompletionModel.wordStart("foo.ba", 6))
        assertEquals(0, CompletionModel.wordStart("abc", 3))
        assertEquals(3, CompletionModel.wordStart("ab ", 3))
    }

    @Test
    fun filterFuzzyMatchesSortsBySortTextAndCapsPerServer() {
        val items = listOf(
            entry("""{"label": "print", "sortText": "b"}"""),
            entry("""{"label": "sprint", "sortText": "a"}"""),
            entry("""{"label": "input", "sortText": "a"}"""),
            entry("""{"label": "pr_x", "sortText": "c"}""", b),
        )
        assertEquals(listOf("sprint", "print", "pr_x"), CompletionModel.filter(items, "pr", 10).map { it.item.label })
        assertEquals(listOf("sprint", "pr_x"), CompletionModel.filter(items, "pr", 1).map { it.item.label })
        assertEquals(4, CompletionModel.filter(items, "", 10).size)
    }

    @Test
    fun filterUsesFilterTextAndPreselectOnlySelects() {
        val items = listOf(
            entry("""{"label": "x", "filterText": "zeta", "sortText": "1"}"""),
            entry("""{"label": "zed", "sortText": "2", "preselect": true}"""),
        )
        val filtered = CompletionModel.filter(items, "ze", 10)
        assertEquals(listOf("x", "zed"), filtered.map { it.item.label })
        assertEquals(1, CompletionModel.initialSelection(filtered))
    }

    @Test
    fun refilterOnlyWhileTypingInTheWord() {
        val origin = CompletionOrigin("x = foo.ba + 1", caret = 10, wordStart = 8)
        assertTrue(CompletionModel.canRefilter(origin, "x = foo.bar + 1", 11))
        assertTrue(CompletionModel.canRefilter(origin, "x = foo.b + 1", 9))
        assertFalse(CompletionModel.canRefilter(origin, "x = foo. + 1", 7))
        assertFalse(CompletionModel.canRefilter(origin, "x = foo.ba( + 1", 11))
        assertFalse(CompletionModel.canRefilter(origin, "y = foo.bar + 1", 11))
    }

    @Test
    fun acceptWithoutEditReplacesTheTypedWord() {
        val origin = CompletionOrigin("foo.pr", 6, 4)
        val r = accept(entry("""{"label": "print"}"""), origin, "foo.pri", 7)
        assertEquals("foo.print", r.text)
        assertEquals(OpenRange(9, 9), r.selection)
        assertNull(r.snippet)
    }

    @Test
    fun textEditEndingAtTheRequestCaretExtendsOverWhatWasTypedSince() {
        // Trigger after ".", item edit is the empty range at the caret; the user typed "pr" since.
        val origin = CompletionOrigin("foo.", 4, 4)
        val e = entry("""{"label": "print", "textEdit": {"range": {"start": {"line": 0, "character": 4}, "end": {"line": 0, "character": 4}}, "newText": "print"}}""")
        assertEquals("foo.print", accept(e, origin, "foo.pr", 6).text)
    }

    @Test
    fun insertReplaceUsesInsertOnEnterAndReplaceOnTab() {
        val origin = CompletionOrigin("pri_old", 3, 0)
        val e = entry(
            """{"label": "print", "textEdit": {"newText": "print",
                "insert": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 3}},
                "replace": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 7}}}}""",
        )
        assertEquals("print_old", accept(e, origin, "pri_old", 3, AcceptMode.INSERT).text)
        assertEquals("print", accept(e, origin, "pri_old", 3, AcceptMode.REPLACE).text)
    }

    @Test
    fun additionalEditsApplyAsOneChangeAndShiftTheCaret() {
        val origin = CompletionOrigin("x\nos.pa", 7, 5)
        val e = entry(
            """{"label": "path", "additionalTextEdits": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}}, "newText": "import os\n"}]}""",
        )
        val r = accept(e, origin, "x\nos.pa", 7)
        assertEquals("import os\nx\nos.path", r.text)
        assertEquals(r.text.length, r.selection.first)
    }

    @Test
    fun snippetItemsOpenASessionOnTheirFirstField() {
        val origin = CompletionOrigin("pr", 2, 0)
        val e = entry("""{"label": "print", "insertText": "print(${'$'}{1:value})${'$'}0", "insertTextFormat": 2}""")
        val r = accept(e, origin, "pr", 2)
        assertEquals("print(value)", r.text)
        assertEquals(OpenRange(6, 11), r.selection)
        assertEquals(listOf(1, 0), r.snippet?.stops?.map { it.index })
    }

    @Test
    fun overlappingEditsAreRefused() {
        val origin = CompletionOrigin("abc", 3, 0)
        val e = entry(
            """{"label": "abc", "additionalTextEdits": [{"range": {"start": {"line": 0, "character": 1}, "end": {"line": 0, "character": 2}}, "newText": "z"}]}""",
        )
        assertNull(CompletionModel.accept(e, origin, "abc", 3, AcceptMode.INSERT, "", "    ") { null })
    }

    @Test
    fun smartEnterNeedsATextualChange() {
        assertFalse(CompletionModel.makesTextualChange(entry("""{"label": "foo"}"""), "foo"))
        assertTrue(CompletionModel.makesTextualChange(entry("""{"label": "foobar"}"""), "foo"))
    }

    @Test
    fun editClassification() {
        assertEquals('x', CompletionModel.typedChar(TextState("ab", 1), TextState("axb", 2)))
        assertNull(CompletionModel.typedChar(TextState("ab", 1), TextState("axyb", 3)))
        assertNull(CompletionModel.typedChar(TextState("ab", 0, 2), TextState("x", 1)))
        assertEquals("\n    ", CompletionModel.insertedText(TextState("a", 1), TextState("a\n    ", 6)))
        assertNull(CompletionModel.insertedText(TextState("ab", 2), TextState("a", 1)))
    }
}
