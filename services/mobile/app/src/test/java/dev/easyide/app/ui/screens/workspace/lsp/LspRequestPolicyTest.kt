package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.lsp.protocol.LspFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspRequestPolicyTest {

    @Test
    fun onlyDocumentAndSafeWorkspaceMethodsPass() {
        assertTrue(LspRequestPolicy.isAllowed("textDocument/formatting"))
        assertTrue(LspRequestPolicy.isAllowed("textDocument/x-vendor"))
        assertTrue(LspRequestPolicy.isAllowed("workspace/symbol"))
        assertTrue(LspRequestPolicy.isAllowed("workspace/executeCommand"))
        listOf("initialize", "shutdown", "exit", "\$/cancelRequest", "workspace/applyEdit", "textDocument/", "textdocument/hover")
            .forEach { assertFalse(it, LspRequestPolicy.isAllowed(it)) }
    }

    @Test
    fun knownMethodsAreGatedByTheirFeature() {
        assertEquals(LspFeature.FORMATTING, LspRequestPolicy.featureOf("textDocument/formatting"))
        assertEquals(LspFeature.WORKSPACE_SYMBOL, LspRequestPolicy.featureOf("workspace/symbol"))
        assertNull(LspRequestPolicy.featureOf("workspace/executeCommand"))
        assertNull(LspRequestPolicy.featureOf("textDocument/x-vendor"))
    }

    @Test
    fun explicitParamsNameTheirDocument() {
        val params = Json.parseToJsonElement("""{"textDocument": {"uri": "file:///workspace/a.py"}, "position": {"line": 0, "character": 1}}""")
        assertEquals("file:///workspace/a.py", LspRequestPolicy.documentUri(params))
        assertNull(LspRequestPolicy.documentUri(Json.parseToJsonElement("""{"query": "x"}""")))
        assertNull(LspRequestPolicy.documentUri(Json.parseToJsonElement("""{"textDocument": {"uri": 3}}""")))
    }

    @Test
    fun applyWorkspaceEditAcceptsTextEditsAndWorkspaceEdits() {
        val text = LspRequestPolicy.editShape(
            Json.parseToJsonElement("""[{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}, "newText": "x"}]"""),
        )
        assertEquals(1, (text as LspRequestPolicy.EditShape.Text).edits.size)
        val ws = LspRequestPolicy.editShape(Json.parseToJsonElement("""{"changes": {"file:///workspace/a.py": []}}"""))
        assertEquals(1, (ws as LspRequestPolicy.EditShape.Workspace).edit.operations.size)
        assertEquals(LspRequestPolicy.EditShape.Nothing, LspRequestPolicy.editShape(JsonNull))
        assertEquals(LspRequestPolicy.EditShape.Invalid, LspRequestPolicy.editShape(JsonPrimitive(3)))
    }

    @Test
    fun messagesShowStringsAsIsAndOtherResultsAsJson() {
        assertEquals("done", LspRequestPolicy.messageText(JsonPrimitive("done")))
        assertEquals("""{"a":1}""", LspRequestPolicy.messageText(Json.parseToJsonElement("""{"a": 1}""")))
    }
}
