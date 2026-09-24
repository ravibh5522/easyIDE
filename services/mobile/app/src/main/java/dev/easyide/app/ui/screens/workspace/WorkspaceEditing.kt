package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.data.UiPreferences
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.files.WorkspaceFileTools
import dev.easyide.app.ui.screens.workspace.find.FindController
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The editing workflows of one workspace, in one holder so [WorkspaceViewModel] wires a single
 * object and the screen takes a single parameter: undo/redo and the signals to the editing
 * surface ([session]), find and replace ([find]), and the project's file knowledge for quick
 * open and the explorer ([files]).
 *
 * The workspace tells it about the events it cannot observe from state: a content change from
 * any source, a tab closing, a rename, a delete.
 */
class WorkspaceEditing(
    scope: CoroutineScope,
    private val state: StateFlow<WorkspaceUiState>,
    selections: EditorSelections,
    decorations: DecorationRegistry,
    setContent: (path: String, text: String) -> Unit,
    projectId: String,
    projectFiles: ProjectFiles,
    preferences: UiPreferences,
) {
    val session: EditorSession = EditorSession(
        selections = selections,
        contentOf = { path -> state.value.openTabs.find { it.relativePath == path }?.content },
        setContent = setContent,
    )

    val find: FindController = FindController(scope, state, selections, decorations, session)

    val files: WorkspaceFileTools = WorkspaceFileTools(projectId, projectFiles, preferences, scope, state)

    /** [path]'s buffer went from [old] to [new], by typing, a language server, an extension or find. */
    fun onContentChanged(path: String, old: String, new: String) = session.onContentChanged(path, old, new)

    fun onTabClosed(path: String) = session.onTabClosed(path)

    fun onRenamed(from: String, to: String) {
        session.onRenamed(from, to)
        files.onRenamed(from, to)
    }

    fun onDeleted(path: String) = files.onDeleted(path)
}
