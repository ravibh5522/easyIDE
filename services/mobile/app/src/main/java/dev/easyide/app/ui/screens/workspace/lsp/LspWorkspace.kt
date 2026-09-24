package dev.easyide.app.ui.screens.workspace.lsp

import androidx.annotation.StringRes
import androidx.compose.ui.text.TextRange
import dev.easyide.app.R
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.SelectionRequest
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.lsp.client.LspClient
import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.text.LineIndex
import dev.easyide.lsp.text.TextEdits
import dev.easyide.lsp.workspace.ApplyResult
import dev.easyide.lsp.workspace.EditPort
import dev.easyide.lsp.workspace.EditResult
import dev.easyide.lsp.workspace.HostLocation
import dev.easyide.sandbox.files.FilePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What the LSP presenters need from the workspace that owns the tabs (the ViewModel). */
interface LspWorkspaceHost {
    val state: StateFlow<WorkspaceUiState>
    fun setContent(path: String, text: String)
    fun openProjectFile(path: String)
    /** Opens (or selects) a tab that is not a project file - an environment file, read-only. */
    fun openTab(tab: EditorTab)
    fun showStatus(message: String)
    /** Runs [command] in a new terminal tab and shows the terminal. */
    fun runInTerminal(command: String)
    fun string(@StringRes id: Int, vararg args: Any): String
}

/** The caret of the active editor as last reported by the surface. */
data class Caret(val path: String, val text: String, val selection: TextRange) {
    val offset: Int get() = selection.end
}

/**
 * Shared state and services of one workspace's LSP presenters: documents, the caret, selection
 * requests to the editor, navigation to locations, and applying server edits to buffers.
 * Presenters run on the main dispatcher and hand heavy work to `Dispatchers.Default`.
 */
class LspWorkspace(
    val client: LspClient,
    val manager: LanguageServerManager,
    val settingsState: StateFlow<SettingsSnapshot>,
    val documents: LspDocuments,
    val host: LspWorkspaceHost,
    val decorations: DecorationRegistry,
    val scope: CoroutineScope,
    val environmentId: String,
    val projectId: String,
    private val rootfsLabel: (File) -> String?,
) {
    private val requestIds = AtomicLong()
    private val selectionFlow = MutableStateFlow<SelectionRequest?>(null)
    private val caretFlow = MutableStateFlow<Caret?>(null)
    private var pendingReveal: Pair<String, Range>? = null

    val selectionRequests: StateFlow<SelectionRequest?> = selectionFlow.asStateFlow()
    val caret: StateFlow<Caret?> = caretFlow.asStateFlow()

    val settings: SettingsSnapshot get() = settingsState.value

    /** [s] resolved for [path]'s language (`[lang]` blocks apply to L-scope keys). */
    fun <T> setting(s: Setting<T>, path: String?): T = settings.get(s, path?.let(documents::doc)?.languageId)

    fun activeTab(): EditorTab? = host.state.value.activeTab

    fun tab(path: String): EditorTab? = host.state.value.openTabs.find { it.relativePath == path }

    fun updateCaret(caret: Caret) {
        caretFlow.value = caret
    }

    /** The caret of the active editor, only while it is a synced document. */
    fun activeCaret(): Caret? = caretFlow.value?.takeIf { it.path == host.state.value.activeTabPath && documents.doc(it.path) != null }

    fun select(path: String, text: String, start: Int, end: Int, reveal: Boolean) {
        selectionFlow.value = SelectionRequest(path, text, start, end, reveal, requestIds.incrementAndGet())
    }

    fun onSelectionApplied(request: SelectionRequest) {
        if (selectionFlow.value?.id == request.id) selectionFlow.value = null
    }

    /** Called on every workspace state change: a tab navigation was waiting for may be open now. */
    fun onTabsChanged(state: WorkspaceUiState) {
        val (path, range) = pendingReveal ?: return
        val tab = state.openTabs.find { it.relativePath == path } ?: return
        pendingReveal = null
        revealIn(tab, range)
    }

    /** A guest uri and range as a location lists can show and navigation can open. */
    fun location(uri: String, range: Range): NavLocation? {
        val host = manager.pathMapper(environmentId, projectId).toHost(uri) ?: return null
        val rel = host.projectRelative
        val label = rel ?: rootfsLabel(host.file) ?: return null
        return NavLocation(rel, host.file, label, range)
    }

    /** Opens [loc] (project file, or environment file read-only) and reveals its range. */
    fun navigate(loc: NavLocation) {
        val path = loc.projectPath ?: envTabPath(loc.label)
        val open = tab(path)
        if (open != null) {
            host.openProjectFile(path)
            revealIn(open, loc.range)
            return
        }
        pendingReveal = path to loc.range
        if (loc.projectPath != null) host.openProjectFile(path) else scope.launch { openEnvFile(loc, path) }
    }

    private suspend fun openEnvFile(loc: NavLocation, path: String) {
        val text = withContext(Dispatchers.IO) {
            try {
                loc.hostFile.takeIf { it.isFile && it.length() <= FilePolicy.TEXT_VIEW_PREFIX_BYTES }?.readText()
            } catch (e: IOException) {
                null
            }
        }
        if (text == null) {
            pendingReveal = null
            host.showStatus(host.string(R.string.lsp_env_file_unreadable, loc.label))
            return
        }
        host.openTab(
            EditorTab(
                relativePath = path,
                name = loc.hostFile.name,
                content = text,
                savedContent = text,
                editable = false,
                notice = host.string(R.string.lsp_env_file_notice, loc.label),
            ),
        )
    }

    private fun revealIn(tab: EditorTab, range: Range) {
        val lines = LineIndex(tab.content)
        select(tab.relativePath, tab.content, lines.offset(range.start), lines.offset(range.start), reveal = true)
    }

    /**
     * Applies [edits] computed for document [version] of [path] to its buffer, as one change.
     * Strict staleness (lsp-features.md 3.2): edits for an older version are refused, with the
     * "document changed" message, because they would corrupt the newer text.
     */
    fun applyToBuffer(path: String, version: Int?, edits: List<TextEdit>): Boolean {
        if (edits.isEmpty()) return true
        val tab = tab(path) ?: return false
        if (version != null && version != documents.version(path)) {
            host.showStatus(host.string(R.string.lsp_document_changed))
            return false
        }
        val next = TextEdits.apply(tab.content, edits) ?: return false
        if (next != tab.content) host.setContent(path, next)
        return true
    }

    /** A server `WorkspaceEdit` (rename, code action) through the `:lsp` applier and [port]. */
    suspend fun applyWorkspaceEdit(edit: WorkspaceEdit, label: String): ApplyResult {
        val result = client.applyEdit(environmentId, projectId, edit, label)
        if (!result.applied) host.showStatus(host.string(R.string.lsp_edit_failed, result.failureReason.orEmpty()))
        return result
    }

    /**
     * The workspace's [EditPort]: text edits to an open editable tab change its buffer (the
     * user saves as usual), anything else goes to [disk].
     */
    fun port(disk: EditPort): EditPort = object : EditPort by disk {
        override suspend fun applyTextEdits(target: HostLocation, edits: List<TextEdit>, label: String): EditResult {
            val path = target.projectRelative ?: return disk.applyTextEdits(target, edits, label)
            return withContext(Dispatchers.Main) {
                val tab = tab(path)?.takeIf { it.editable } ?: return@withContext null
                val next = TextEdits.apply(tab.content, edits) ?: return@withContext EditResult.Failed(host.string(R.string.lsp_edit_overlap, tab.name))
                if (next != tab.content) host.setContent(path, next)
                EditResult.Ok
            } ?: disk.applyTextEdits(target, edits, label)
        }
    }

    private fun envTabPath(label: String): String = "$ENV_TAB_PREFIX$label"

    private companion object {
        /** Environment files open under a path no project file can have, so they never collide. */
        const val ENV_TAB_PREFIX = "env:"
    }
}
