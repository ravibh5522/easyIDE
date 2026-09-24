package dev.easyide.app.ui.screens.workspace.decor

import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.CodeLenses
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.Diagnostics
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.DocumentHighlights
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.GutterMarkers
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.InlayHints
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer.SearchMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorationModelTest {

    private val text = "val x = foo(1)\nval y = bar(2)\n"

    private fun error(start: Int, end: Int) = DiagnosticDecoration(start, end, DiagnosticSeverity.ERROR)

    private fun model(vararg diagnostics: DiagnosticDecoration) = DecorationModel().apply {
        set(Diagnostics, SERVER, diagnostics.toList(), text)
    }

    private fun DecorationModel.diagnostics() = state.value.items(Diagnostics)

    @Test fun `a decoration before the edit is untouched`() {
        val m = model(error(4, 5))
        m.syncText(text.replace("bar", "bazz"))
        assertEquals(listOf(error(4, 5)), m.diagnostics())
    }

    @Test fun `a decoration after the edit moves by the edit's length change`() {
        val y = text.indexOf("y")
        val m = model(error(y, y + 1))
        m.syncText("// comment\n$text")
        assertEquals(listOf(error(y + 11, y + 12)), m.diagnostics())
    }

    @Test fun `deleting lines above moves a decoration up`() {
        val bar = text.indexOf("bar")
        val m = model(error(bar, bar + 3))
        m.syncText(text.substringAfter('\n'))
        val shifted = text.indexOf('\n') + 1
        assertEquals(listOf(error(bar - shifted, bar + 3 - shifted)), m.diagnostics())
    }

    @Test fun `typing inside a diagnostic drops it until the next result`() {
        val foo = text.indexOf("foo")
        val m = model(error(foo, foo + 3), error(0, 3))
        m.syncText(text.replace("foo", "fXoo"))
        assertEquals(listOf(error(0, 3)), m.diagnostics())
    }

    @Test fun `typing right after a diagnostic neither drops nor extends it`() {
        val foo = text.indexOf("foo")
        val m = model(error(foo, foo + 3))
        m.syncText(text.replace("foo(", "foo2("))
        assertEquals(listOf(error(foo, foo + 3)), m.diagnostics())
    }

    @Test fun `typing right before a diagnostic pushes it`() {
        val foo = text.indexOf("foo")
        val m = model(error(foo, foo + 3))
        m.syncText(text.replace("= foo", "= _foo"))
        assertEquals(listOf(error(foo + 1, foo + 4)), m.diagnostics())
    }

    @Test fun `clip layers keep the surviving part of a range`() {
        // Gutter markers and code lenses are points; CLIP pushes them past a replacement.
        val m = DecorationModel()
        m.set(GutterMarkers, SERVER, listOf(GutterMarkerDecoration(6, GutterGlyph.LIGHTBULB)), text)
        m.syncText(text.replaceRange(4, 8, "zz"))
        assertEquals(listOf(GutterMarkerDecoration(6, GutterGlyph.LIGHTBULB)), m.state.value.items(GutterMarkers))
    }

    @Test fun `a code lens at a line start survives typing on that line`() {
        val lineTwo = text.indexOf('\n') + 1
        val m = DecorationModel()
        m.set(CodeLenses, SERVER, listOf(CodeLensDecoration(lineTwo, "2 references", "lens-1")), text)
        m.syncText(text.replaceRange(lineTwo + 4, lineTwo + 5, "why"))
        assertEquals(lineTwo, m.state.value.items(CodeLenses).single().offset)
    }

    @Test fun `an inlay hint inside a deleted span is dropped, one after it moves`() {
        val m = DecorationModel()
        m.set(InlayHints, SERVER, listOf(InlayHintDecoration(5, ": Int"), InlayHintDecoration(20, ": Int")), text)
        m.syncText(text.removeRange(3, 7))
        assertEquals(listOf(InlayHintDecoration(16, ": Int")), m.state.value.items(InlayHints))
    }

    @Test fun `appending to the identifier an inlay hint follows keeps the hint after it`() {
        val m = DecorationModel()
        m.set(InlayHints, SERVER, listOf(InlayHintDecoration(5, ": Int")), text)
        m.syncText(text.replaceRange(5, 5, "yz"))
        assertEquals(listOf(InlayHintDecoration(7, ": Int")), m.state.value.items(InlayHints))
    }

    @Test fun `results positioned against a newer text carry older decorations forward first`() {
        val m = DecorationModel()
        val y = text.indexOf("y")
        m.set(SearchMatches, FIND, listOf(SearchMatchDecoration(y, y + 1, isCurrent = true)), text)

        val newer = "\n\n$text"
        m.set(Diagnostics, SERVER, listOf(error(2, 5)), newer)

        assertEquals(newer, m.state.value.text)
        assertEquals(listOf(SearchMatchDecoration(y + 2, y + 3, true)), m.state.value.items(SearchMatches))
        assertEquals(listOf(error(2, 5)), m.state.value.items(Diagnostics))
    }

    @Test fun `out-of-range items from a producer are dropped at the boundary`() {
        val m = model(error(-1, 2), error(3, 2), error(0, text.length + 1), error(0, text.length), error(3, 3))
        assertEquals(listOf(error(0, text.length), error(3, 3)), m.diagnostics())
    }

    @Test fun `sources are independent`() {
        val m = model(error(0, 3))
        m.set(Diagnostics, OTHER, listOf(error(4, 5)), text)
        m.set(Diagnostics, SERVER, emptyList(), text)
        assertEquals(listOf(error(4, 5)), m.diagnostics())
        assertEquals(setOf(OTHER), m.state.value.sources(Diagnostics))

        m.set(DocumentHighlights, OTHER, listOf(HighlightDecoration(0, 3, HighlightKind.READ)), text)
        m.clearSource(OTHER)
        assertTrue(m.state.value.isEmpty)
    }

    @Test fun `clearing one source in one layer leaves the rest`() {
        val m = model(error(0, 3))
        m.set(SearchMatches, FIND, listOf(SearchMatchDecoration(0, 3, false)), text)
        m.clear(SearchMatches, FIND)
        assertEquals(listOf(error(0, 3)), m.diagnostics())
        assertTrue(m.state.value.items(SearchMatches).isEmpty())
    }

    @Test fun `range queries find long decorations that start before the window`() {
        val m = model(error(0, text.length), error(20, 21), error(25, 26))
        assertEquals(listOf(error(0, text.length), error(20, 21)), m.state.value.inRange(Diagnostics, 18, 22))
    }

    @Test fun `range queries merge sources in start order`() {
        val m = model(error(10, 11))
        m.set(Diagnostics, OTHER, listOf(error(2, 3), error(12, 13)), text)
        assertEquals(listOf(error(2, 3), error(10, 11), error(12, 13)), m.diagnostics())
    }

    @Test fun `syncing the same text publishes nothing new`() {
        val m = model(error(0, 3))
        val before = m.state.value
        m.syncText(text)
        assertSame(before, m.state.value)
    }

    @Test fun `many edits in sequence keep ranges on their text`() {
        val source = "a\n".repeat(200)
        val marks = (0 until 200 step 10).map { line -> error(line * 2, line * 2 + 1) }
        val m = DecorationModel().apply { set(Diagnostics, SERVER, marks, source) }
        var current = source
        repeat(50) { i ->
            current = "#$i\n$current"
            m.syncText(current)
        }
        val items = m.diagnostics()
        assertEquals(marks.size, items.size)
        items.forEach { assertEquals("a", current.substring(it.start, it.end)) }
    }

    @Test fun `the registry keeps a model per path across renames and drops it on close`() {
        val registry = DecorationRegistry()
        val a = registry.model("a.py")
        assertSame(a, registry.model("a.py"))
        registry.rename("a.py", "b.py")
        assertSame(a, registry.model("b.py"))
        registry.remove("b.py")
        assertTrue(registry.model("b.py") !== a)
    }

    private companion object {
        const val SERVER = "python/basedpyright"
        const val OTHER = "python/ruff"
        const val FIND = "find"
    }
}
