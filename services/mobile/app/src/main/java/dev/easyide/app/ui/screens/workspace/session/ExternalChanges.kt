package dev.easyide.app.ui.screens.workspace.session

import android.content.Context
import dev.easyide.app.R
import dev.easyide.app.session.DiskState
import dev.easyide.app.session.ExternalChange
import dev.easyide.app.session.ExternalChangeDetector
import dev.easyide.app.session.ExternalState
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * External change detection for open buffers (S8). The terminal, `git pull` and other apps
 * write project files without going through the editor; without this a buffer silently
 * shows text the disk no longer holds, and saving it overwrites the other change.
 *
 * Checks read the file and compare text ([ExternalChangeDetector]); they run when the
 * watcher reports a directory that holds an open tab, and when the workspace comes back to
 * the screen. Checks are serialised so a burst of events cannot interleave two decisions
 * about one tab.
 */
internal class ExternalChanges(
    private val projectId: String,
    private val state: MutableStateFlow<WorkspaceUiState>,
    private val projectFiles: ProjectFiles,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val setStatus: (String) -> Unit,
) {
    private val turn = Mutex()

    /** Every editable open tab, e.g. when the workspace is shown again. */
    fun checkAll() = check { true }

    /** The tabs whose file is directly in one of [dirs] (project-relative, `""` for the root). */
    fun checkDirs(dirs: Set<String>) = check { tab -> tab.relativePath.substringBeforeLast('/', "") in dirs }

    /**
     * Applies the user's choice for a conflicted tab. Either way the disk text becomes the
     * tab's saved baseline, so the tab is dirty exactly when it differs from what is on disk
     * now; "use disk" also replaces the buffer.
     */
    fun resolve(path: String, keepMine: Boolean) {
        state.update { s ->
            s.copy(openTabs = s.openTabs.map { tab ->
                val conflict = tab.externalState as? ExternalState.Conflict
                if (tab.relativePath != path || conflict == null) tab
                else tab.copy(
                    content = if (keepMine) tab.content else conflict.diskText,
                    savedContent = conflict.diskText,
                    externalState = ExternalState.InSync,
                )
            })
        }
    }

    /** The save guard's message for a conflicted tab; saving would overwrite the outside change. */
    fun blockedMessage(tab: EditorTab): String = appContext.getString(R.string.session_save_blocked, tab.name)

    /** What disk holds for [path] now; also how a restore reads the files it reopens. */
    suspend fun readDisk(path: String): DiskState {
        if (projectFiles.fileSize(projectId, path).getOrNull() == null) return DiskState.Missing
        val text = projectFiles.open(projectId, path).getOrNull() as? FileContent.Text
        return if (text != null && text.editable && !text.truncated) DiskState.Text(text.text) else DiskState.NotText
    }

    private fun check(select: (EditorTab) -> Boolean) {
        scope.launch {
            turn.withLock {
                state.value.openTabs.filter { it.editable && select(it) }.forEach { tab ->
                    applyChange(tab.relativePath, readDisk(tab.relativePath))
                }
            }
        }
    }

    /**
     * Decided against the tab as it is when the result lands, not as it was when the read
     * started: the user may have typed or saved in between.
     */
    private fun applyChange(path: String, disk: DiskState) {
        var applied: ExternalChange = ExternalChange.None
        var name = ""
        state.update { s ->
            val tab = s.openTabs.find { it.relativePath == path } ?: return@update s
            applied = ExternalChangeDetector.classify(tab.content, tab.savedContent, disk, tab.externalState)
            name = tab.name
            val next = when (val change = applied) {
                ExternalChange.None -> tab
                is ExternalChange.Reload -> tab.copy(content = change.text, savedContent = change.text, externalState = ExternalState.InSync)
                is ExternalChange.Sync -> tab.copy(savedContent = change.text, externalState = ExternalState.InSync)
                is ExternalChange.Conflict -> tab.copy(externalState = ExternalState.Conflict(change.diskText))
                ExternalChange.Gone -> tab.copy(externalState = ExternalState.Gone)
                ExternalChange.Resync -> tab.copy(externalState = ExternalState.InSync)
            }
            if (next === tab) s else s.copy(openTabs = s.openTabs.map { if (it === tab) next else it })
        }
        when (applied) {
            is ExternalChange.Reload -> setStatus(appContext.getString(R.string.session_reloaded, name))
            ExternalChange.Gone -> setStatus(appContext.getString(R.string.session_gone, name))
            else -> Unit
        }
    }
}
