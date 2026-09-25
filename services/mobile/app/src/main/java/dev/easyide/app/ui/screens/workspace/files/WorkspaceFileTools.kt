package dev.easyide.app.ui.screens.workspace.files

import dev.easyide.app.data.UiPreferences
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the workspace knows about its project's files beyond the explorer's lazily listed tree:
 * the files opened recently (persisted per project), the `.gitignore` rules seen so far, and
 * the flat file index quick open searches.
 *
 * Everything here observes [state] or is asked by the UI, so the workspace's own file actions
 * (open, rename, delete) do not need to know it exists.
 */
class WorkspaceFileTools(
    private val projectId: String,
    private val projectFiles: ProjectFiles,
    private val preferences: UiPreferences,
    private val scope: CoroutineScope,
    state: StateFlow<WorkspaceUiState>,
) {
    private val recentState = MutableStateFlow(RecentFiles())
    private val ignoreState = MutableStateFlow(IgnoreIndex.EMPTY)
    private val indexState = MutableStateFlow(FileIndex.EMPTY)
    private val indexingState = MutableStateFlow(false)
    private var indexJob: Job? = null

    private val ignoreLoader = IgnoreLoader { path -> projectFiles.readText(projectId, path).getOrNull() }

    val recent: StateFlow<RecentFiles> = recentState.asStateFlow()

    /** The `.gitignore` rules of every listed directory that has one; the explorer filters with it. */
    val ignore: StateFlow<IgnoreIndex> = ignoreState.asStateFlow()

    val index: StateFlow<FileIndex> = indexState.asStateFlow()

    /** True while [refreshIndex] walks the tree; the list may then still be the previous walk's. */
    val indexing: StateFlow<Boolean> = indexingState.asStateFlow()

    init {
        // Merged, not assigned: a file opened before the stored list was read must stay first.
        scope.launch {
            val stored = preferences.recentFiles(projectId).first()
            recentState.update { RecentFiles((it.paths + stored).distinct().take(RecentFiles.MAX)) }
        }
        // Opening or switching to a project file makes it the most recent.
        scope.launch {
            state.map { it.activeTabPath }.distinctUntilChanged().collect { path ->
                if (path != null && !path.startsWith(VIRTUAL_TAB_PREFIX)) update { it.touched(path) }
            }
        }
        // Whenever a listing changes, re-read the .gitignore files it shows (they may have been edited).
        scope.launch {
            state.map { it.tree to it.childrenByDir }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second === b.second }
                .collect { (tree, children) ->
                    var next = ignoreLoader.refresh(ignoreState.value, "", tree)
                    for ((dir, nodes) in children) next = ignoreLoader.refresh(next, dir, nodes)
                    ignoreState.value = next
                }
        }
    }

    /** Re-walks the project for quick open, off the main thread; the previous result stays until the new one lands. */
    fun refreshIndex(hideHidden: Boolean) {
        indexJob?.cancel()
        indexJob = scope.launch {
            indexingState.value = true
            try {
                val indexer = FileIndexer({ dir -> projectFiles.list(projectId, dir).getOrNull() }, ignoreLoader)
                indexState.value = withContext(Dispatchers.IO) { indexer.build(hideHidden) }
            } finally {
                indexingState.value = false
            }
        }
    }

    fun onRenamed(from: String, to: String) = update { it.renamed(from, to) }

    fun onDeleted(path: String) = update { it.without(path) }

    private fun update(change: (RecentFiles) -> RecentFiles) {
        val next = change(recentState.value)
        if (next == recentState.value) return
        recentState.update { next }
        scope.launch { preferences.setRecentFiles(projectId, next.paths) }
    }

    private companion object {
        /**
         * Tabs for environment files (navigation into a toolchain's sources) are keyed with this
         * prefix by the language-server host so they never collide with a project path; they
         * are not project files and never belong in the recent list.
         */
        const val VIRTUAL_TAB_PREFIX = "env:"
    }
}
