package dev.easyide.app.ui.screens.workspace.session

import android.content.Context
import dev.easyide.app.R
import dev.easyide.app.diagnostics.LogSink
import dev.easyide.app.session.ExternalState
import dev.easyide.app.session.PathRemap
import dev.easyide.app.session.SessionPolicy
import dev.easyide.app.session.SessionStore
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.workspace.WorkspaceShellModel
import dev.easyide.app.ui.shell.workspace.forWorkspace
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the workspace screen needs of its session, kept apart from the view model's own callbacks. */
class WorkspaceSessionUi(
    val shell: WorkspaceShellModel,
    val scrolls: EditorScrolls,
    /** Applies the user's answer to the conflict dialog: keep the buffer, or take the disk text. */
    val onResolveConflict: (path: String, keepMine: Boolean) -> Unit,
)

/** What the session needs the view model to do: things only it can. */
internal interface WorkspaceSessionHost {
    fun refreshTree()

    /** The set of watched directories changed (tabs opened or closed, folders expanded). */
    fun syncFileWatcher()

    fun showStatus(message: String)
}

/**
 * Everything about a workspace that outlives a screen or a process, next to the view model
 * that owns the live state: scroll and layout (kept while parked), the hot-exit snapshot and
 * its restore ([SessionPersistence], [SessionRestore]), external change detection
 * ([ExternalChanges]) and rename-follow. See docs/decision/0023-workspace-session-lifetime.md.
 */
internal class WorkspaceSession(
    private val projectId: String,
    private val state: MutableStateFlow<WorkspaceUiState>,
    private val selections: EditorSelections,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val host: WorkspaceSessionHost,
    projectFiles: ProjectFiles,
    store: SessionStore,
    io: CoroutineDispatcher,
    clock: () -> Long,
    log: LogSink,
    private val restoreOpenTabs: suspend () -> Boolean,
) {
    private val scrolls = EditorScrolls()
    private val shell = AppDocuments.registries(appContext::getString).forWorkspace().let { WorkspaceShellModel(it.documents, it.containers) }
    private val changes = ExternalChanges(projectId, state, projectFiles, scope, appContext, host::showStatus)
    private val persistence = SessionPersistence(projectId, state, selections, scrolls, shell::snapshot, store, io, clock, log)
    private val restore = SessionRestore(projectId, store, projectFiles, io)

    val ui = WorkspaceSessionUi(shell, scrolls, changes::resolve)

    /** Directories that hold an editable open tab: the watcher must cover them to see outside edits. */
    val tabDirectories: Flow<Set<String>> = state.map(::tabDirs).distinctUntilChanged()

    fun openTabDirectories(): Set<String> = tabDirs(state.value)

    private fun tabDirs(s: WorkspaceUiState): Set<String> =
        s.openTabs.filter { it.editable }.map { it.relativePath.substringBeforeLast('/', "") }.toSet()

    /**
     * Restores the stored session (after [settled], the previous same-project session that
     * was still writing its final save), then begins persisting. Persisting starts only
     * afterwards: writing the empty state of a just-created workspace over the stored
     * session would destroy what is about to be restored.
     */
    fun start(settled: Job?): Job = scope.launch {
        settled?.join()
        restore.load(restoreOpenTabs())?.let { apply(it) }
        merge(
            state.map { Triple(it.openTabs, it.activeTabPath, it.expandedDirs) }.distinctUntilChanged().map { },
            selections.changes.map { },
            scrolls.changes.map { },
            shell.snapshots.map { },
        ).conflate().collect {
            delay(SessionPolicy.SAVE_DELAY_MS)
            persistence.save()
        }
    }

    private fun apply(restored: RestoredSession) {
        restored.shell?.let(shell::restore)
        state.update { s ->
            val open = s.openTabs.map { it.relativePath }.toSet()
            s.copy(
                openTabs = restored.tabs.map { it.tab }.filter { it.relativePath !in open } + s.openTabs,
                activeTabPath = s.activeTabPath ?: restored.activePath,
                expandedDirs = s.expandedDirs + restored.expandedDirs,
            )
        }
        restored.tabs.forEach {
            selections[it.tab.relativePath] = it.caret
            scrolls.record(it.tab.relativePath, it.scroll)
        }
        host.refreshTree()
        host.syncFileWatcher()
        val unsaved = restored.unsavedCount
        if (unsaved > 0) host.showStatus(appContext.resources.getQuantityString(R.plurals.session_restored_unsaved, unsaved, unsaved))
    }

    /** Writes the session now (leaving the foreground, parking, eviction). */
    suspend fun flush() = persistence.save()

    fun onResumed() = changes.checkAll()

    fun checkChangedDirs(dirs: Set<String>) = changes.checkDirs(dirs)

    /** The message for a save refused because the tab's file changed underneath it. */
    fun blockedSave(tab: EditorTab): String? =
        if (tab.externalState is ExternalState.Conflict) changes.blockedMessage(tab) else null

    /**
     * Retargets tabs (and their scroll) when [from] became [to]: a file, or a directory whose
     * files have tabs. [moved] carries a tab's old and new path to the per-path stores that
     * live outside the session (decorations, carets).
     */
    fun followRename(from: String, to: String, moved: (old: String, new: String) -> Unit) {
        val renamed = state.value.openTabs.mapNotNull { tab -> PathRemap.remap(tab.relativePath, from, to)?.let { tab.relativePath to it } }
        if (renamed.isEmpty()) return
        val targets = renamed.toMap()
        state.update { s ->
            s.copy(
                openTabs = s.openTabs.map { tab ->
                    val new = targets[tab.relativePath] ?: return@map tab
                    // The file moved with its name: a "deleted" flag from the watcher racing this rename is stale.
                    val flag = if (tab.externalState == ExternalState.Gone) ExternalState.InSync else tab.externalState
                    tab.copy(relativePath = new, name = new.substringAfterLast('/'), externalState = flag)
                },
                activeTabPath = s.activeTabPath?.let { targets[it] ?: it },
            )
        }
        renamed.forEach { (old, new) ->
            scrolls.rename(old, new)
            moved(old, new)
        }
    }

    /**
     * Ends persistence. [discard] also deletes the stored session (explicit close); without
     * it the last write stays, so the workspace restores next time.
     */
    fun end(discard: Boolean) = if (discard) persistence.discard() else persistence.stop()
}
