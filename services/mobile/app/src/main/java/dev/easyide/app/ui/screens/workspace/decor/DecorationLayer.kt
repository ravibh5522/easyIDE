package dev.easyide.app.ui.screens.workspace.decor

/** What happens to a decoration when an edit touches the text it covers. */
enum class EditPolicy {
    /**
     * Remove it: the text it described is gone, so it is wrong until a fresh result arrives.
     * The rule lsp-features.md 3.3 sets for diagnostics; also right for highlights and matches,
     * which are recomputed on every change anyway.
     */
    DROP_ON_OVERLAP,

    /**
     * Keep it, cut to the text that survived (a point is pushed past the new text). For
     * line-anchored markers, which should stay on their line while it is being typed in.
     */
    CLIP,
}

/**
 * A typed slot in a [DecorationSet]. Each layer holds one decoration type, knows how an edit
 * affects it, and paints in [priority] order among the layers sharing a render pass (higher
 * paints later, on top): the current search match must stay visible over a symbol highlight.
 *
 * A sealed hierarchy rather than an enum so [DecorationSet.items] can return `List<T>`
 * without casts at the call site; adding a layer is one object here plus its painter.
 */
sealed class DecorationLayer<T : Decoration>(
    val id: String,
    val priority: Int,
    val onEdit: EditPolicy,
) {
    /** [item] moved to a new span by an edit. */
    abstract fun moved(item: T, start: Int, end: Int): T

    data object SearchMatches : DecorationLayer<SearchMatchDecoration>("searchMatches", 20, EditPolicy.DROP_ON_OVERLAP) {
        override fun moved(item: SearchMatchDecoration, start: Int, end: Int) = item.copy(start = start, end = end)
    }

    data object DocumentHighlights : DecorationLayer<HighlightDecoration>("documentHighlights", 10, EditPolicy.DROP_ON_OVERLAP) {
        override fun moved(item: HighlightDecoration, start: Int, end: Int) = item.copy(start = start, end = end)
    }

    data object Diagnostics : DecorationLayer<DiagnosticDecoration>("diagnostics", 30, EditPolicy.DROP_ON_OVERLAP) {
        override fun moved(item: DiagnosticDecoration, start: Int, end: Int) = item.copy(start = start, end = end)
    }

    data object InlayHints : DecorationLayer<InlayHintDecoration>("inlayHints", 40, EditPolicy.DROP_ON_OVERLAP) {
        override fun moved(item: InlayHintDecoration, start: Int, end: Int) = item.copy(offset = start)
    }

    data object GutterMarkers : DecorationLayer<GutterMarkerDecoration>("gutterMarkers", 50, EditPolicy.CLIP) {
        override fun moved(item: GutterMarkerDecoration, start: Int, end: Int) = item.copy(offset = start)
    }

    data object CodeLenses : DecorationLayer<CodeLensDecoration>("codeLenses", 60, EditPolicy.CLIP) {
        override fun moved(item: CodeLensDecoration, start: Int, end: Int) = item.copy(offset = start)
    }
}
