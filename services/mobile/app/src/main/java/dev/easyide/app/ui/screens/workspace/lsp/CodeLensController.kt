package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.R
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.ui.screens.workspace.decor.CodeLensDecoration
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The lenses of one gutter line, listed in an editor popup anchored at that line. */
data class CodeLensMenuUi(val path: String, val anchor: Int, val lenses: List<LensEntry>)

/**
 * Code lens (LSP-35, lsp-features.md 4.16). Requested for the visible document after edits
 * (debounced), on open, when a server starts and on `workspace/codeLens/refresh`; lenses in
 * the visible lines without a command are resolved lazily. Rendered per decision 0018 as a
 * gutter glyph (CLIP policy, so a lens stays on its line while it is edited); a tap on the
 * glyph lists that line's lenses, and choosing one runs its command: the references list for
 * `editor.action.showReferences`, otherwise `workspace/executeCommand` on the lens's server.
 * `editor.codeLens` (per language) off clears them and sends nothing.
 */
class CodeLensController(
    private val ws: LspWorkspace,
    private val showLocations: (title: String, locations: List<NavLocation>) -> Unit,
) {
    private val entries = HashMap<String, List<LensEntry>>()
    private val asked = HashMap<String, MutableSet<String>>()
    private val visible = HashMap<String, IntRange>()
    private val fetchJobs = HashMap<String, Job>()
    private val menuState = MutableStateFlow<CodeLensMenuUi?>(null)

    val menu: StateFlow<CodeLensMenuUi?> = menuState.asStateFlow()

    fun refresh(path: String, debounce: Boolean) {
        fetchJobs.remove(path)?.cancel()
        if (!ws.setting(LspSettingsSchema.codeLens, path)) return clear(path)
        val ctx = ws.documents.context(path) ?: return clear(path)
        fetchJobs[path] = ws.scope.launch {
            if (debounce) delay(LspUiPolicy.VIEWPORT_FEATURES_DEBOUNCE_MS)
            val text = ws.tab(path)?.content ?: return@launch
            ws.documents.ensureCurrent(path, text)
            val found = ws.client.codeLenses(ctx)
            // Lenses are positioned against the version the server saw; place them on that text
            // and let the decoration model carry them onto the buffer.
            val version = found.firstOrNull()?.version ?: ws.documents.version(path)
            val base = ws.documents.textAt(path, version) ?: return@launch
            val list = CodeLensModel.entries(found, version)
            entries[path] = list
            asked[path] = HashSet()
            publish(path, list, base)
            resolveVisible(path)
        }
    }

    fun onVisibleLines(path: String, first: Int, last: Int) {
        if (visible[path] == first..last) return
        visible[path] = first..last
        if (path !in entries && path !in fetchJobs) refresh(path, debounce = false) else resolveVisible(path)
    }

    fun retain(paths: Set<String>) {
        for (path in (entries.keys + fetchJobs.keys) - paths) {
            fetchJobs.remove(path)?.cancel()
            entries.remove(path)
            asked.remove(path)
        }
        visible.keys.retainAll(paths)
        if (menuState.value?.path?.let { it !in paths } == true) menuState.value = null
    }

    /** Gutter tap: lists the lenses on [line]; false when it has none. */
    fun onGutterTap(path: String, line: Int): Boolean {
        val list = entries[path] ?: return false
        val set = ws.decorations.model(path).state.value
        val lines = LineIndex(set.text)
        val ids = set.items(DecorationLayer.CodeLenses).filter { lines.position(it.offset).line == line }.mapTo(HashSet()) { it.id }
        val onLine = list.filter { it.id in ids }
        if (onLine.isEmpty()) return false
        menuState.value = CodeLensMenuUi(path, lines.offset(Position(line, 0)), onLine)
        return true
    }

    fun closeMenu() {
        menuState.value = null
    }

    fun run(entry: LensEntry) {
        closeMenu()
        ws.scope.launch {
            val resolved = if (entry.command == null) ws.client.resolveCodeLens(ws.environmentId, ws.projectId, entry.lens) else entry.lens.value
            when (val action = CodeLensModel.actionFor(resolved.command)) {
                is LensAction.ShowLocations -> {
                    val locs = action.locations.mapNotNull { ws.location(it.uri, it.range) }
                    if (locs.isEmpty()) ws.host.showStatus(ws.host.string(R.string.lsp_code_lens_no_locations))
                    else showLocations(resolved.command?.title.orEmpty(), locs)
                }
                is LensAction.Execute -> ws.client.executeCommand(ws.environmentId, ws.projectId, entry.lens.server, action.command)
                LensAction.None -> Unit
            }
        }
    }

    private fun resolveVisible(path: String) {
        val list = entries[path] ?: return
        val window = visible[path] ?: return
        val done = asked.getOrPut(path) { HashSet() }
        val pending = CodeLensModel.toResolve(list, window, done, LspUiPolicy.MAX_CODE_LENS_RESOLVES)
        if (pending.isEmpty()) return
        done += pending.map { it.id }
        ws.scope.launch {
            val resolved = pending.map { e ->
                async { e.id to ws.client.resolveCodeLens(ws.environmentId, ws.projectId, e.lens) }
            }.awaitAll().toMap()
            val current = entries[path] ?: return@launch
            if (current !== list) return@launch
            val next = current.map { e -> resolved[e.id]?.let { e.copy(lens = e.lens.copy(value = it)) } ?: e }
            entries[path] = next
            menuState.value?.takeIf { it.path == path }?.let { m ->
                menuState.value = m.copy(lenses = m.lenses.map { l -> next.firstOrNull { it.id == l.id } ?: l })
            }
            // Titles are only read by the popup; the decorations (and their offsets) stay.
        }
    }

    private fun publish(path: String, list: List<LensEntry>, text: String) {
        val lines = LineIndex(text)
        val items = list.map { CodeLensDecoration(lines.offset(Position(it.line, 0)), it.title.orEmpty(), it.id) }
        ws.decorations.model(path).set(DecorationLayer.CodeLenses, SOURCE, items, text)
    }

    private fun clear(path: String) {
        entries.remove(path)
        asked.remove(path)
        ws.decorations.model(path).clear(DecorationLayer.CodeLenses, SOURCE)
        if (menuState.value?.path == path) menuState.value = null
    }

    private companion object {
        const val SOURCE = "lsp:codeLens"
    }
}
