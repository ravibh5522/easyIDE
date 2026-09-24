package dev.easyide.app.ui.screens.workspace.decor

import org.junit.Assert.assertEquals
import org.junit.Test

class GutterGlyphsTest {

    // Ten lines of "abcd\n": line n starts at offset 5n.
    private val text = "abcd\n".repeat(10)
    private val lineOf = { offset: Int -> offset / 5 }

    private fun model() = DecorationModel()

    @Test fun `the highest-ranked glyph wins a line`() {
        val m = model()
        m.set(DecorationLayer.GutterMarkers, "lsp", listOf(GutterMarkerDecoration(10, GutterGlyph.LIGHTBULB)), text)
        m.set(DecorationLayer.CodeLenses, "lsp", listOf(CodeLensDecoration(10, "run", "l1")), text)
        m.set(
            DecorationLayer.Diagnostics, "lsp",
            listOf(
                DiagnosticDecoration(11, 12, DiagnosticSeverity.WARNING),
                DiagnosticDecoration(12, 13, DiagnosticSeverity.ERROR),
                DiagnosticDecoration(20, 21, DiagnosticSeverity.WARNING),
            ),
            text,
        )
        val glyphs = GutterGlyphs.byLine(m.state.value, 0, text.length, lineOf)
        assertEquals(mapOf(2 to GutterGlyph.ERROR, 4 to GutterGlyph.WARNING), glyphs)
    }

    @Test fun `hints and gutter-disabled diagnostics leave the gutter alone`() {
        val m = model()
        m.set(
            DecorationLayer.Diagnostics, "lsp",
            listOf(
                DiagnosticDecoration(0, 1, DiagnosticSeverity.HINT),
                DiagnosticDecoration(5, 6, DiagnosticSeverity.ERROR, showInGutter = false),
            ),
            text,
        )
        m.set(DecorationLayer.CodeLenses, "lsp", listOf(CodeLensDecoration(5, "2 refs", "l1")), text)
        assertEquals(mapOf(1 to GutterGlyph.CODE_LENS), GutterGlyphs.byLine(m.state.value, 0, text.length, lineOf))
    }

    @Test fun `a diagnostic starting above the window marks no visible line`() {
        val m = model()
        m.set(DecorationLayer.Diagnostics, "lsp", listOf(DiagnosticDecoration(0, 30, DiagnosticSeverity.ERROR)), text)
        assertEquals(emptyMap<Int, GutterGlyph>(), GutterGlyphs.byLine(m.state.value, 20, 40, lineOf))
    }

    @Test fun `severity maps to glyph except hint`() {
        assertEquals(
            listOf(GutterGlyph.ERROR, GutterGlyph.WARNING, GutterGlyph.INFORMATION, null),
            DiagnosticSeverity.entries.map(GutterGlyphs::forSeverity),
        )
    }
}
