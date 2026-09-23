package dev.easyide.app.ui.screens.workspace.decor

/**
 * Which glyph each gutter line shows. Pure so the ranking is testable without a layout.
 *
 * Sources: diagnostics that opted into the gutter (hint severity has no glyph, as in
 * VS Code), explicit gutter markers, and code lenses. One glyph per line; the lowest
 * [GutterGlyph] ordinal wins.
 */
internal object GutterGlyphs {

    fun forSeverity(severity: DiagnosticSeverity): GutterGlyph? = when (severity) {
        DiagnosticSeverity.ERROR -> GutterGlyph.ERROR
        DiagnosticSeverity.WARNING -> GutterGlyph.WARNING
        DiagnosticSeverity.INFORMATION -> GutterGlyph.INFORMATION
        DiagnosticSeverity.HINT -> null
    }

    /**
     * Glyph per line for decorations touching offsets `[from, to]`, keyed by [lineOf] of each
     * decoration's start (a multi-line diagnostic marks the line it starts on).
     */
    fun byLine(set: DecorationSet, from: Int, to: Int, lineOf: (Int) -> Int): Map<Int, GutterGlyph> {
        val out = HashMap<Int, GutterGlyph>()
        fun offer(offset: Int, glyph: GutterGlyph) {
            val line = lineOf(offset)
            val held = out[line]
            if (held == null || glyph.ordinal < held.ordinal) out[line] = glyph
        }
        for (d in set.inRange(DecorationLayer.Diagnostics, from, to)) {
            if (d.start < from || !d.showInGutter) continue
            forSeverity(d.severity)?.let { offer(d.start, it) }
        }
        for (m in set.inRange(DecorationLayer.GutterMarkers, from, to)) offer(m.offset, m.glyph)
        for (lens in set.inRange(DecorationLayer.CodeLenses, from, to)) offer(lens.offset, GutterGlyph.CODE_LENS)
        return out
    }
}
