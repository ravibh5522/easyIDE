package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.R
import dev.easyide.app.ui.commands.fuzzyFilter
import dev.easyide.lsp.protocol.Range
import dev.easyide.sandbox.files.FilePolicy
import dev.easyide.lsp.protocol.EditOperation
import dev.easyide.lsp.protocol.NavTarget
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.PrepareRename
import dev.easyide.lsp.protocol.SymbolKind
import dev.easyide.lsp.protocol.SymbolNode
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.session.LspRequestException
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The definition family (LSP-24); all four share one result shape. */
enum class NavKind { DEFINITION, DECLARATION, TYPE_DEFINITION, IMPLEMENTATION, REFERENCES }

/** One hit in a location list, with the text of its line for context. */
data class LocationRow(val location: NavLocation, val preview: String)

data class LocationGroup(val label: String, val rows: List<LocationRow>)

/** The References panel (or a multi-target definition list). */
data class LocationsUi(val kind: NavKind, val symbol: String, val groups: List<LocationGroup>)

enum class SymbolScope { DOCUMENT, WORKSPACE }

/** [span] is the whole symbol (its body), where [location] is its name; null for a workspace symbol, which carries no body. */
data class SymbolRow(val name: String, val detail: String?, val kind: SymbolKind, val depth: Int, val location: NavLocation, val span: Range? = null)

/** The `@` / `#` quick pick. */
data class SymbolPickerUi(val scope: SymbolScope, val query: String, val rows: List<SymbolRow>, val loading: Boolean)

/** Rename input, prefilled with [placeholder] (lsp-features.md 4.9 step 2). */
data class RenameUi(val path: String, val text: String, val offset: Int, val placeholder: String)

/** A rename's `WorkspaceEdit` shown before it is applied: files and edit counts. */
data class RenamePreviewUi(val edit: WorkspaceEdit, val newName: String, val files: List<Pair<String, Int>>, val path: String, val version: Int?)

/**
 * Navigation and project-wide edits: go to definition and friends, find references, document
 * and workspace symbols, rename with preview (LSP-24, 25, 27, 28, 31).
 */
class NavigationController(private val ws: LspWorkspace) {

    private val locationsState = MutableStateFlow<LocationsUi?>(null)
    private val pickerState = MutableStateFlow<SymbolPickerUi?>(null)
    private val renameState = MutableStateFlow<RenameUi?>(null)
    private val previewState = MutableStateFlow<RenamePreviewUi?>(null)
    private val outlineState = MutableStateFlow<List<SymbolRow>>(emptyList())

    val locations: StateFlow<LocationsUi?> = locationsState.asStateFlow()
    val picker: StateFlow<SymbolPickerUi?> = pickerState.asStateFlow()
    val rename: StateFlow<RenameUi?> = renameState.asStateFlow()
    val renamePreview: StateFlow<RenamePreviewUi?> = previewState.asStateFlow()

    /** The active document's symbols, flattened with depth, for the Outline panel. */
    val outline: StateFlow<List<SymbolRow>> = outlineState.asStateFlow()

    private var pickerJob: Job? = null

    fun closeLocations() {
        locationsState.value = null
    }

    /** F12 and friends at [offset] (the caret when null): one target opens, several list. */
    fun goTo(kind: NavKind, path: String? = null, offset: Int? = null) {
        val caret = ws.activeCaret()
        val p = path ?: caret?.path ?: return
        val tab = ws.tab(p) ?: return
        val at = offset ?: caret?.offset ?: return
        val ctx = ws.documents.context(p) ?: return
        ws.scope.launch {
            ws.documents.ensureCurrent(p, tab.content)
            val lines = LineIndex(tab.content)
            val pos = lines.position(at)
            val symbol = tab.content.substring(CompletionModel.wordStart(tab.content, at), wordEnd(tab.content, at))
            if (kind == NavKind.REFERENCES) {
                val refs = ws.client.references(ctx, pos, includeDeclaration = true)
                val locs = refs.mapNotNull { ws.location(it.value.uri, it.value.range) }
                if (locs.isEmpty()) return@launch ws.host.showStatus(ws.host.string(R.string.lsp_no_references, symbol))
                locationsState.value = LocationsUi(kind, symbol, group(locs))
                return@launch
            }
            val targets: List<NavTarget> = when (kind) {
                NavKind.DEFINITION -> ws.client.definition(ctx, pos)
                NavKind.DECLARATION -> ws.client.declaration(ctx, pos)
                NavKind.TYPE_DEFINITION -> ws.client.typeDefinition(ctx, pos)
                NavKind.IMPLEMENTATION -> ws.client.implementation(ctx, pos)
                NavKind.REFERENCES -> null
            }?.value.orEmpty()
            val locs = targets.mapNotNull { ws.location(it.uri, it.range) }
            when {
                locs.isEmpty() -> ws.host.showStatus(ws.host.string(R.string.lsp_no_definition, symbol))
                locs.size == 1 -> ws.navigate(locs.single())
                else -> locationsState.value = LocationsUi(kind, symbol, group(locs))
            }
        }
    }

    /** A location list from outside the built-in commands (an extension's `lspRequest`), under [title]. */
    suspend fun showLocations(title: String, locs: List<NavLocation>) {
        locationsState.value = LocationsUi(NavKind.REFERENCES, title, group(locs))
    }

    // ---- symbols ------------------------------------------------------------------------

    fun openPicker(scope: SymbolScope, query: String = "") {
        pickerState.value = SymbolPickerUi(scope, query, emptyList(), loading = true)
        onPickerQuery(query)
    }

    fun closePicker() {
        pickerJob?.cancel()
        pickerState.value = null
    }

    fun onPickerQuery(query: String) {
        val cur = pickerState.value ?: return
        pickerState.value = cur.copy(query = query, loading = true)
        pickerJob?.cancel()
        pickerJob = ws.scope.launch {
            val rows = when (cur.scope) {
                SymbolScope.DOCUMENT -> documentSymbols()?.let { all -> fuzzyRows(all, query) }.orEmpty()
                SymbolScope.WORKSPACE -> {
                    delay(LspUiPolicy.WORKSPACE_SYMBOL_DEBOUNCE_MS)
                    workspaceSymbols(query)
                }
            }
            pickerState.value = pickerState.value?.copy(rows = rows, loading = false)
        }
    }

    /** Refreshes the Outline for the active document (on open, save and tab switch). */
    fun refreshOutline() {
        ws.scope.launch { outlineState.value = documentSymbols().orEmpty() }
    }

    private suspend fun documentSymbols(): List<SymbolRow>? {
        val tab = ws.activeTab() ?: return null
        val ctx = ws.documents.context(tab.relativePath) ?: return null
        ws.documents.ensureCurrent(tab.relativePath, tab.content)
        val nodes = ws.client.documentSymbols(ctx).map { it.value }
        return withContext(Dispatchers.Default) { flatten(nodes, 0) { r -> ws.location(ctx.uri, r) } }
    }

    private suspend fun workspaceSymbols(query: String): List<SymbolRow> {
        val languages = ws.documents.open.map { it.languageId }.distinct()
        return languages.flatMap { lang ->
            ws.client.workspaceSymbols(ws.environmentId, ws.projectId, lang, query).mapNotNull { s ->
                val loc = ws.location(s.value.location.uri, s.value.location.range) ?: return@mapNotNull null
                SymbolRow(s.value.name, listOfNotNull(s.value.containerName, loc.label).joinToString(" - "), s.value.kind, 0, loc)
            }
        }.distinctBy { it.name to it.location }
    }

    private fun fuzzyRows(rows: List<SymbolRow>, query: String): List<SymbolRow> =
        if (query.isBlank()) rows else fuzzyFilter(query, rows) { it.name }

    // ---- rename ---------------------------------------------------------------------------

    fun startRename() {
        val caret = ws.activeCaret() ?: return
        val ctx = ws.documents.context(caret.path) ?: return
        ws.scope.launch {
            ws.documents.ensureCurrent(caret.path, caret.text)
            val lines = LineIndex(caret.text)
            val prepared = try {
                ws.client.prepareRename(ctx, lines.position(caret.offset))
            } catch (e: LspRequestException) {
                null
            }
            val word = caret.text.substring(CompletionModel.wordStart(caret.text, caret.offset), wordEnd(caret.text, caret.offset))
            val placeholder = when (val p = prepared?.value) {
                null -> return@launch ws.host.showStatus(ws.host.string(R.string.lsp_cannot_rename))
                is PrepareRename.At -> p.placeholder ?: caret.text.substring(lines.offset(p.range.start), lines.offset(p.range.end))
                else -> word
            }
            if (placeholder.isEmpty()) return@launch ws.host.showStatus(ws.host.string(R.string.lsp_cannot_rename))
            renameState.value = RenameUi(caret.path, caret.text, caret.offset, placeholder)
        }
    }

    fun cancelRename() {
        renameState.value = null
        previewState.value = null
    }

    /** Asks the owner for the edit and shows the preview; nothing changes yet. */
    fun submitRename(newName: String) {
        val r = renameState.value ?: return
        renameState.value = null
        val ctx = ws.documents.context(r.path) ?: return
        ws.scope.launch {
            val result = try {
                ws.client.rename(ctx, LineIndex(r.text).position(r.offset), newName)
            } catch (e: LspRequestException) {
                return@launch ws.host.showStatus(ws.host.string(R.string.lsp_rename_failed, e.message.orEmpty()))
            } ?: return@launch ws.host.showStatus(ws.host.string(R.string.lsp_cannot_rename))
            val files = result.value.operations.map { op ->
                when (op) {
                    is EditOperation.Text -> label(op.uri) to op.edits.size
                    is EditOperation.Create -> label(op.uri) to 1
                    is EditOperation.Rename -> label(op.oldUri) to 1
                    is EditOperation.Delete -> label(op.uri) to 1
                }
            }
            previewState.value = RenamePreviewUi(result.value, newName, files, r.path, result.version)
        }
    }

    /** Applies the previewed edit, unless the document changed since it was computed. */
    fun confirmRename() {
        val p = previewState.value ?: return
        previewState.value = null
        if (p.version != null && p.version != ws.documents.version(p.path)) {
            return ws.host.showStatus(ws.host.string(R.string.lsp_document_changed))
        }
        ws.scope.launch { ws.applyWorkspaceEdit(p.edit, ws.host.string(R.string.lsp_rename_label, p.newName)) }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun label(uri: String): String = ws.location(uri, ZERO_RANGE)?.label ?: uri

    /** Grouped by file (sorted by label), rows by position, each with its line's text. */
    private suspend fun group(locs: List<NavLocation>): List<LocationGroup> = withContext(Dispatchers.IO) {
        locs.groupBy { it.label }.toSortedMap().map { (label, list) ->
            val sorted = list.sortedWith(compareBy<NavLocation> { it.range.start.line }.thenBy { it.range.start.character })
            val lines = sorted.first().let { l -> ws.tab(l.projectPath ?: "")?.content ?: readQuietly(l) }?.lines()
            LocationGroup(label, sorted.map { l -> LocationRow(l, lines?.getOrNull(l.range.start.line)?.trim().orEmpty()) })
        }
    }

    private fun readQuietly(l: NavLocation): String? = try {
        l.hostFile.takeIf { it.isFile && it.length() <= FilePolicy.TEXT_EDIT_MAX_BYTES }?.readText()
    } catch (e: IOException) {
        null
    }

    private fun wordEnd(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && CompletionModel.isWordChar(text[i])) i++
        return i
    }

    companion object {
        private val ZERO_RANGE = Range(Position(0, 0), Position(0, 0))

        /** Depth-first flattening of the outline tree, children indented under their parent. */
        fun flatten(nodes: List<SymbolNode>, depth: Int, locate: (Range) -> NavLocation?): List<SymbolRow> =
            nodes.sortedWith(compareBy<SymbolNode> { it.selectionRange.start.line }.thenBy { it.selectionRange.start.character })
                .flatMap { n ->
                    val self = locate(n.selectionRange)?.let { SymbolRow(n.name, n.detail, n.kind, depth, it, n.range) }
                    listOfNotNull(self) + flatten(n.children, depth + 1, locate)
                }
    }
}
