package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.ui.text.TextRange
import dev.easyide.app.session.BackupRef
import dev.easyide.app.session.DiskState
import dev.easyide.app.session.ExternalChangeDetector
import dev.easyide.app.session.ExternalState
import dev.easyide.app.session.LayoutSnapshot
import dev.easyide.app.session.SessionStore
import dev.easyide.app.session.StoredSession
import dev.easyide.app.session.TabSnapshot
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.editorTabFor
import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.FilePolicy
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A tab read back from a stored session, with the view state to give it. */
internal class RestoredTab(val tab: EditorTab, val caret: TextRange, val scroll: ScrollPos)

internal class RestoredSession(
    val tabs: List<RestoredTab>,
    val activePath: String?,
    val expandedDirs: Set<String>,
    val layout: LayoutSnapshot?,
) {
    /** Tabs that come back holding edits the disk does not have. */
    val unsavedCount: Int get() = tabs.count { it.tab.isDirty }
}

/**
 * Reads a stored session back into tabs: files are opened afresh from disk, and a tab that
 * had unsaved edits gets its backup text back, classified against what the disk says now
 * ([ExternalChangeDetector.restore]). Terminals are not restored (they were processes).
 */
internal class SessionRestore(
    private val projectId: String,
    private val store: SessionStore,
    private val projectFiles: ProjectFiles,
    private val io: CoroutineDispatcher,
) {
    /**
     * [restoreTabs] false (`workspace.restoreOpenTabs`) brings back only tabs with unsaved
     * edits: that text exists nowhere else, so no setting may discard it. Null when nothing
     * is stored or it is unreadable.
     */
    suspend fun load(restoreTabs: Boolean): RestoredSession? {
        val stored = withContext(io) { store.load(projectId) } ?: return null
        val snapshot = stored.snapshot
        val tabs = snapshot.tabs
            .filter { restoreTabs || it.backup != null }
            .mapNotNull { restoreTab(it, stored) }
        return RestoredSession(
            tabs = tabs,
            activePath = snapshot.activePath?.takeIf { active -> tabs.any { it.tab.relativePath == active } },
            expandedDirs = if (restoreTabs) snapshot.expandedDirs.toSet() else emptySet(),
            layout = snapshot.layout,
        )
    }

    private suspend fun restoreTab(saved: TabSnapshot, stored: StoredSession): RestoredTab? {
        val node = FileNode(saved.path.substringAfterLast('/'), saved.path, isDirectory = false, sizeBytes = 0)
        val disk = projectFiles.open(projectId, saved.path).getOrNull()
        val backup = saved.backup?.let { ref -> withContext(io) { stored.backupText(ref) }?.let { ref to it } }
        val tab = (if (backup != null) dirtyTab(node, disk, backup) else disk?.let { editorTabFor(node, it) {} })
            ?.copy(showPreview = saved.showPreview)
            ?: return null
        return RestoredTab(tab, TextRange(saved.caretStart, saved.caretEnd), ScrollPos(saved.scrollY, saved.scrollX))
    }

    private fun dirtyTab(node: FileNode, disk: FileContent?, backup: Pair<BackupRef, String>): EditorTab {
        val (ref, text) = backup
        val editableText = (disk as? FileContent.Text)?.takeIf { it.editable && !it.truncated }
        val state = when {
            editableText != null -> DiskState.Text(editableText.text)
            disk == null -> DiskState.Missing
            else -> DiskState.NotText
        }
        val buffer = ExternalChangeDetector.restore(text, ref.baseSha256, state)
        val base = editableText?.let { editorTabFor(node, it) {} }
            ?: EditorTab(node.relativePath, node.name, content = text, savedContent = "", highlightingEnabled = text.length <= FilePolicy.HIGHLIGHT_MAX_BYTES)
        return base.copy(content = buffer.content, savedContent = buffer.savedContent, externalState = buffer.state)
    }
}
