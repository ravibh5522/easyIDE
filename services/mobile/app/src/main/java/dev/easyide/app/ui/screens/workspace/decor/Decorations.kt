package dev.easyide.app.ui.screens.workspace.decor

/**
 * Something painted over, under or beside the document text without being part of it.
 *
 * Positions are a half-open `[start, end)` span in UTF-16 code units of the whole document,
 * the unit of both Kotlin strings and LSP's default `positionEncoding`. Offsets rather than
 * (line, column): an edit on another line moves an offset by one addition, and offsets are
 * what `TextLayoutResult` answers in, so painting needs no conversion. LSP presenters convert
 * `Position` to an offset once, when a result arrives.
 *
 * Point decorations (inlay hints, gutter markers, code lens) have `start == end`.
 */
sealed interface Decoration {
    val start: Int
    val end: Int
}

/** LSP `DiagnosticSeverity`, in the protocol's order (1 = error ... 4 = hint). */
enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }

/**
 * A diagnostic's squiggle and gutter icon. The two flags carry the per-language
 * `editor.diagnostics.showSquiggles` / `showInGutter` settings, resolved by the presenter,
 * so one stored range drives both surfaces and both move together on an edit.
 */
data class DiagnosticDecoration(
    override val start: Int,
    override val end: Int,
    val severity: DiagnosticSeverity,
    val showSquiggle: Boolean = true,
    val showInGutter: Boolean = true,
) : Decoration

/** LSP `DocumentHighlightKind`, in the protocol's order (1 = text ... 3 = write). */
enum class HighlightKind { TEXT, READ, WRITE }

/** An occurrence of the symbol at the caret (`textDocument/documentHighlight`). */
data class HighlightDecoration(
    override val start: Int,
    override val end: Int,
    val kind: HighlightKind,
) : Decoration

/** A find result; [isCurrent] marks the one the caret or selection sits on. */
data class SearchMatchDecoration(
    override val start: Int,
    override val end: Int,
    val isCurrent: Boolean,
) : Decoration

/**
 * An inlay hint anchored between two characters at [offset].
 *
 * Rendered as end-of-line ghost text for now (decision 0018): inserting it inline would make
 * the text field re-lay-out the whole document on every hint refresh. [label] is the hint's
 * label parts already joined, padding included.
 */
data class InlayHintDecoration(val offset: Int, val label: String) : Decoration {
    override val start: Int get() = offset
    override val end: Int get() = offset
}

/**
 * The icons the gutter can show, one per line. Declaration order is rank: when several
 * decorations want the same line, the earliest entry wins, so an error is never hidden by
 * a lightbulb.
 */
enum class GutterGlyph { ERROR, WARNING, INFORMATION, LIGHTBULB, CODE_LENS }

/** A gutter icon for the line containing [offset] (lightbulb, and later breakpoints). */
data class GutterMarkerDecoration(val offset: Int, val glyph: GutterGlyph) : Decoration {
    override val start: Int get() = offset
    override val end: Int get() = offset
}

/**
 * A code lens anchored to the line containing [offset]. Shown as a gutter glyph until the
 * editor can insert between-line blocks (decision 0018); a gutter tap lists the lenses on
 * that line. [id] is the presenter's own key for running the lens's command.
 */
data class CodeLensDecoration(val offset: Int, val title: String, val id: String) : Decoration {
    override val start: Int get() = offset
    override val end: Int get() = offset
}
