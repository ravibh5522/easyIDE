package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.SemanticHighlighting
import dev.easyide.app.ui.screens.workspace.syntax.SemanticOverlay
import dev.easyide.lsp.client.FromServer
import dev.easyide.lsp.client.SemanticTokensResult
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Semantic tokens (LSP-33, lsp-features.md 4.13): requested for the visible document after
 * each edit (debounced), on open and on `workspace/semanticTokens/refresh`; `full/delta`
 * comes from the `:lsp` cache. A document longer than [LspUiPolicy.SEMANTIC_RANGE_FIRST_LINES]
 * gets the viewport's range first so the screen colours before the full set arrives.
 *
 * Results become a [SemanticOverlay] on the text of the version they were computed for; the
 * renderer shifts it onto newer text (ShiftAdjusted), so typing never blanks the colours of
 * other lines. Decoding and layout run on `Dispatchers.Default`; the main thread only swaps
 * the map. Overlays of closed documents are dropped.
 *
 * Gated per language by `editor.semanticHighlighting.enabled`: off clears the overlay and
 * sends nothing; `configuredByTheme` follows [onThemeFlag].
 */
class SemanticTokensController(private val ws: LspWorkspace) {

    private val state = MutableStateFlow<Map<String, SemanticOverlay>>(emptyMap())
    private val jobs = HashMap<String, Job>()
    private val visible = HashMap<String, IntRange>()
    private var themeWantsTokens = true

    /** Overlay per open document path; the editor paints the active one. */
    val overlays: StateFlow<Map<String, SemanticOverlay>> = state.asStateFlow()

    /** The active theme's `semanticHighlighting` flag, for `configuredByTheme`. */
    fun onThemeFlag(enabled: Boolean) {
        if (enabled == themeWantsTokens) return
        themeWantsTokens = enabled
        ws.activeTab()?.relativePath?.let { refresh(it, debounce = false) }
    }

    fun enabledFor(path: String): Boolean = when (ws.setting(LspSettingsSchema.semanticHighlighting, path)) {
        SemanticHighlighting.ON -> true
        SemanticHighlighting.OFF -> false
        SemanticHighlighting.CONFIGURED_BY_THEME -> themeWantsTokens
    }

    fun onTextChanged(path: String) = refresh(path, debounce = true)

    fun onVisibleLines(path: String, first: Int, last: Int) {
        visible[path] = first..last
        // A document without an overlay yet (opened, or the server just started) asks now.
        if (state.value[path] == null) refresh(path, debounce = false)
    }

    /** Drops overlays and pending requests of documents that are no longer synced. */
    fun retain(paths: Set<String>) {
        (jobs.keys - paths).forEach { jobs.remove(it)?.cancel() }
        visible.keys.retainAll(paths)
        if (state.value.keys.any { it !in paths }) state.update { m -> m.filterKeys { it in paths } }
    }

    fun refresh(path: String, debounce: Boolean) {
        jobs.remove(path)?.cancel()
        if (!enabledFor(path)) return clear(path)
        val ctx = ws.documents.context(path) ?: return clear(path)
        jobs[path] = ws.scope.launch {
            if (debounce) delay(LspUiPolicy.SEMANTIC_TOKENS_DEBOUNCE_MS)
            val text = ws.tab(path)?.content ?: return@launch
            ws.documents.ensureCurrent(path, text)
            val window = visible[path]
            if (state.value[path] == null && window != null) {
                val lines = LineIndex(text)
                if (lines.lineCount > LspUiPolicy.SEMANTIC_RANGE_FIRST_LINES) {
                    val range = viewport(lines, window)
                    install(path, withContext(Dispatchers.Default) { ws.client.semanticTokensRange(ctx, range) })
                }
            }
            install(path, withContext(Dispatchers.Default) { ws.client.semanticTokens(ctx) })
        }
    }

    private suspend fun install(path: String, result: FromServer<SemanticTokensResult>?) {
        result ?: return
        // The text the server tokenised; older than the buffer is fine (the overlay shifts),
        // unknown (history rolled over) is not.
        val text = ws.documents.textAt(path, result.version) ?: return
        val overlay = withContext(Dispatchers.Default) {
            SemanticOverlay.build(text, result.value.tokens, LspUiPolicy.MAX_SEMANTIC_TOKENS)
        }
        if (ws.documents.doc(path) == null || !enabledFor(path)) return
        state.update { it + (path to overlay) }
    }

    private fun clear(path: String) {
        if (path in state.value) state.update { it - path }
    }

    private fun viewport(lines: LineIndex, window: IntRange): Range {
        val first = (window.first - LspUiPolicy.VIEWPORT_MARGIN_LINES).coerceAtLeast(0)
        val last = (window.last + LspUiPolicy.VIEWPORT_MARGIN_LINES).coerceAtMost(lines.lineCount - 1)
        return Range(Position(first, 0), lines.position(lines.offset(Position(last + 1, 0))))
    }
}
