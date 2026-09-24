package dev.easyide.app.ui.screens.workspace.syntax

import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.lsp.protocol.SemanticToken
import dev.easyide.lsp.protocol.SemanticTokensCodec
import dev.easyide.lsp.protocol.SemanticTokensLegend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticOverlayTest {

    private val text = "class A:\n    def f(self):\n        return self\n"

    private fun tok(line: Int, start: Int, length: Int, type: String, vararg mods: String) =
        SemanticToken(line, start, length, type, mods.toSet())

    private val tokens = listOf(
        tok(0, 6, 1, "class", "declaration"),
        tok(1, 8, 1, "method", "declaration"),
        tok(1, 10, 4, "parameter"),
        tok(2, 15, 4, "parameter"),
    )

    private fun spans(lines: Array<List<SemanticSpan>?>, line: Int) = lines[line]?.map { Triple(it.start, it.end, it.key.type) }

    @Test
    fun buildLaysTokensOutPerLineAndInternsKeys() {
        val o = SemanticOverlay.build(text, tokens)
        assertEquals(4, o.lineCount)
        assertEquals(listOf(Triple(8, 9, "method"), Triple(10, 14, "parameter")), spans(o.linesFor(text), 1))
        assertNull(o.spansOn(3))
        assertSame(o.spansOn(1)!![1].key, o.spansOn(2)!![0].key)
    }

    @Test
    fun decodedServerStreamBuildsTheSameOverlay() {
        val legend = SemanticTokensLegend(listOf("class", "method", "parameter"), listOf("declaration"))
        val data = intArrayOf(0, 6, 1, 0, 1, 1, 8, 1, 1, 1, 0, 2, 4, 2, 0, 1, 15, 4, 2, 0)
        val o = SemanticOverlay.build(text, SemanticTokensCodec.decode(data, legend))
        assertEquals(setOf("declaration"), o.spansOn(0)!!.single().key.modifiers)
        assertEquals(15, o.spansOn(2)!!.single().start)
    }

    @Test
    fun tokensPastTheLineOrTheDocumentAreClippedOrDropped() {
        val o = SemanticOverlay.build("ab\ncd", listOf(tok(0, 1, 10, "variable"), tok(5, 0, 1, "variable"), tok(1, 2, 1, "variable")))
        assertEquals(1 to 2, o.spansOn(0)!!.single().let { it.start to it.end })
        assertNull(o.spansOn(1))
    }

    @Test
    fun tokenCountIsBounded() {
        val many = (0 until 100).map { tok(0, it, 1, "variable") }
        assertEquals(10, SemanticOverlay.build("x".repeat(100), many, maxTokens = 10).spansOn(0)!!.size)
    }

    @Test
    fun editInsideALineDropsOnlyThatLine() {
        val o = SemanticOverlay.build(text, tokens)
        val lines = o.linesFor(text.replace("def f(self)", "def f(self, x)"))
        assertEquals(listOf(Triple(6, 7, "class")), spans(lines, 0))
        assertNull(lines[1])
        assertEquals(listOf(Triple(15, 19, "parameter")), spans(lines, 2))
    }

    @Test
    fun insertedLinesShiftTheLinesBelow() {
        val o = SemanticOverlay.build(text, tokens)
        val edited = "# header\n# two\n$text"
        val lines = o.linesFor(edited)
        assertNull(lines[0])
        assertNull(lines[1])
        assertEquals(listOf(Triple(6, 7, "class")), spans(lines, 2))
        assertEquals(listOf(Triple(15, 19, "parameter")), spans(lines, 4))
        assertSame("memoised for the same text", lines, o.linesFor(edited))
    }

    @Test
    fun deletedLinesPullTheLinesBelowUp() {
        val o = SemanticOverlay.build(text, tokens)
        val lines = o.linesFor("class A:\n        return self\n")
        assertEquals(3, lines.size)
        assertEquals(listOf(Triple(6, 7, "class")), spans(lines, 0))
        assertEquals(listOf(Triple(15, 19, "parameter")), spans(lines, 1))
    }

    @Test
    fun everyAdvertisedTypeHasARole() {
        assertTrue(SemanticRules.TOKEN_TYPES.all { SemanticRules.roleFor(it, emptySet()) != null })
        assertEquals(SyntaxRole.CONSTANT, SemanticRules.roleFor("variable", setOf("readonly")))
        assertEquals(SyntaxRole.VARIABLE, SemanticRules.roleFor("variable", setOf("declaration")))
        assertNull(SemanticRules.roleFor("somethingNew", emptySet()))
    }
}
