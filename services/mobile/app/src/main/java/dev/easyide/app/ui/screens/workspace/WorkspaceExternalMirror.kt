package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fires [ProjectManager]'s mirror calls for one workspace and turns a failure
 * into a status message, so every call site in [WorkspaceViewModel] does not
 * need to repeat the same "launch, then report on failure" boilerplate.
 *
 * Every call here runs after the app-private write it follows has already
 * succeeded - a sync problem (permission revoked, SD card removed, provider
 * rejected the write) is something to tell the user about, never a reason to
 * treat the save/create/rename/delete they asked for as having failed.
 */
internal class WorkspaceExternalMirror(
    private val projectManager: ProjectManager,
    private val projectId: String,
    private val scope: CoroutineScope,
    private val onSyncFailed: (String) -> Unit,
) {
    fun write(relativePath: String, content: ByteArray) = launch {
        projectManager.mirrorWrite(projectId, relativePath, content)
    }

    fun createDirectory(relativePath: String) = launch {
        projectManager.mirrorCreateDirectory(projectId, relativePath)
    }

    fun delete(relativePath: String) = launch {
        projectManager.mirrorDelete(projectId, relativePath)
    }

    /** Mirrors whatever is currently on disk at [relativePath] - file or directory. */
    fun path(relativePath: String) = launch {
        projectManager.mirrorPath(projectId, relativePath)
    }

    private fun launch(action: suspend () -> Result<Unit>) {
        scope.launch {
            action().onFailure { cause -> onSyncFailed(cause.message ?: "failed") }
        }
    }
}
