package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.lsp.client.FromServer
import dev.easyide.lsp.protocol.CodeLens
import dev.easyide.lsp.protocol.Command
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.session.ServerKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLensModelTest {

    private val server = ServerKey("e", "p", "gopls")

    private fun lens(line: Int, title: String?) = FromServer(
        server,
        CodeLens(Range(Position(line, 0), Position(line, 4)), title?.let { Command(it, "gopls.run", null) }, JsonObject(emptyMap())),
        3,
    )

    @Test
    fun entriesAreKeyedByVersionAndAnchoredOnTheirStartLine() {
        val e = CodeLensModel.entries(listOf(lens(2, "run test"), lens(9, null)), 3)
        assertEquals(listOf("3:0", "3:1"), e.map { it.id })
        assertEquals(listOf(2, 9), e.map { it.line })
        assertEquals("run test", e[0].title)
        assertEquals(null, e[1].title)
        assertEquals(listOf(e[1]), CodeLensModel.onLine(e, 9))
    }

    @Test
    fun onlyVisibleUnresolvedLensesAreResolvedWithinTheLimit() {
        val e = CodeLensModel.entries((0 until 20).map { lens(it, if (it % 2 == 0) "t" else null) }, 1)
        val pending = CodeLensModel.toResolve(e, 0..9, alreadyAsked = setOf("1:1"), limit = 3)
        assertEquals(listOf(3, 5, 7), pending.map { it.line })
        assertTrue(CodeLensModel.toResolve(e, 30..40, emptySet(), 10).isEmpty())
    }

    @Test
    fun showReferencesOpensTheLocationListOtherCommandsExecute() {
        val args = Json.parseToJsonElement(
            """["file:///w/a.go", {"line":1,"character":0},
               [{"uri":"file:///w/b.go","range":{"start":{"line":3,"character":1},"end":{"line":3,"character":4}}}]]""",
        ) as JsonArray
        val show = CodeLensModel.actionFor(Command("2 references", "editor.action.showReferences", args))
        assertEquals(listOf("file:///w/b.go"), (show as LensAction.ShowLocations).locations.map { it.uri })
        val run = Command("run test", "gopls.test", null)
        assertEquals(LensAction.Execute(run), CodeLensModel.actionFor(run))
        assertEquals(LensAction.None, CodeLensModel.actionFor(null))
        assertEquals(LensAction.None, CodeLensModel.actionFor(Command("3 implementations", "", null)))
        // Malformed client-command arguments still go to the server rather than being dropped.
        val bad = Command("refs", "editor.action.showReferences", null)
        assertEquals(LensAction.Execute(bad), CodeLensModel.actionFor(bad))
    }
}
