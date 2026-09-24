package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.InlayHintsMode
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.app.ui.screens.workspace.decor.HighlightDecoration
import dev.easyide.app.ui.screens.workspace.decor.HighlightKind
import dev.easyide.app.ui.screens.workspace.decor.InlayHintDecoration
import dev.easyide.lsp.protocol.DocumentHighlightKind
import dev.easyide.lsp.protocol.InlayHint
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Document highlights (LSP-26) on caret idle and inlay hints (LSP-32) for the visible lines,
 * both written into the document's decoration model. Highlights are cleared by any edit;
 * inlay hints are refreshed after edits and scrolls, and hidden while Ctrl+Alt is held under
 * `onUnlessPressed`.
 */
class CaretDecorations(private val ws: LspWorkspace) {

    private var highlightJob: Job? = null
    private var inlayJob: Job? = null
    private val visible = HashMap<String, IntRange>()
    private var modifiersHeld = false

    fun onCaret(caret: Caret, textChanged: Boolean) {
        highlightJob?.cancel()
        val model = ws.decorations.model(caret.path)
        if (textChanged) model.clear(DecorationLayer.DocumentHighlights, SOURCE)
        if (!ws.setting(LspSettingsSchema.occurrencesHighlight, caret.path)) return model.clear(DecorationLayer.DocumentHighlights, SOURCE)
        val ctx = ws.documents.context(caret.path) ?: return
        highlightJob = ws.scope.launch {
            delay(LspUiPolicy.DOCUMENT_HIGHLIGHT_DEBOUNCE_MS)
            ws.documents.ensureCurrent(caret.path, caret.text)
            val lines = LineIndex(caret.text)
            val found = ws.client.documentHighlights(ctx, lines.position(caret.offset))
            if (ws.tab(caret.path)?.content != caret.text) return@launch
            val items = found.map { h ->
                HighlightDecoration(lines.offset(h.value.range.start), lines.offset(h.value.range.end), kindOf(h.value.kind))
            }
            model.set(DecorationLayer.DocumentHighlights, SOURCE, items, caret.text)
        }
        if (textChanged) refreshInlays(caret.path)
    }

    fun onVisibleLines(path: String, first: Int, last: Int) {
        if (visible[path] == first..last) return
        visible[path] = first..last
        refreshInlays(path)
    }

    /** Ctrl+Alt pressed or released (hardware keyboard), for `onUnlessPressed`. */
    fun onModifiers(held: Boolean) {
        if (held == modifiersHeld) return
        modifiersHeld = held
        ws.activeTab()?.relativePath?.let(::refreshInlays)
    }

    fun refreshInlays(path: String) {
        inlayJob?.cancel()
        val model = ws.decorations.model(path)
        val mode = ws.setting(LspSettingsSchema.inlayHints, path)
        if (mode == InlayHintsMode.OFF || (mode == InlayHintsMode.ON_UNLESS_PRESSED && modifiersHeld)) {
            return model.clear(DecorationLayer.InlayHints, SOURCE)
        }
        val ctx = ws.documents.context(path) ?: return
        val window = visible[path] ?: return
        inlayJob = ws.scope.launch {
            delay(LspUiPolicy.VIEWPORT_FEATURES_DEBOUNCE_MS)
            val text = ws.tab(path)?.content ?: return@launch
            ws.documents.ensureCurrent(path, text)
            val lines = LineIndex(text)
            val range = Range(Position(window.first, 0), lines.position(lines.offset(Position(window.last + 1, 0))))
            val hints = ws.client.inlayHints(ctx, range)
            if (ws.tab(path)?.content != text) return@launch
            model.set(DecorationLayer.InlayHints, SOURCE, hints.map { decoration(it.value, lines) }, text)
        }
    }

    private fun decoration(h: InlayHint, lines: LineIndex): InlayHintDecoration {
        val label = buildString {
            if (h.paddingLeft) append(' ')
            append(h.text)
            if (h.paddingRight) append(' ')
        }
        return InlayHintDecoration(lines.offset(h.position), label)
    }

    private fun kindOf(k: DocumentHighlightKind): HighlightKind = when (k) {
        DocumentHighlightKind.TEXT -> HighlightKind.TEXT
        DocumentHighlightKind.READ -> HighlightKind.READ
        DocumentHighlightKind.WRITE -> HighlightKind.WRITE
    }

    private companion object {
        const val SOURCE = "lsp"
    }
}
