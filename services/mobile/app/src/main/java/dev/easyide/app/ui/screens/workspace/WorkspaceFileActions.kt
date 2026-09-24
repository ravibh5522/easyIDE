package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the file actions need from the workspace, so they stay testable without a ViewModel. */
internal interface FileActionHost {
    fun refreshTree()
    fun setStatus(message: String)
    fun closeTab(path: String)

    /** Every per-path store (tabs, scroll, carets, decorations) follows a moved file or folder. */
    fun followRename(from: String, to: String)

    /** The file no longer exists, so editing state keyed by it is dropped. */
    fun forget(path: String)
}

/** Create, rename, delete, copy, cut and paste for the explorer; each mirrors to the external folder. */
internal class WorkspaceFileActions(
    private val projectId: String,
    private val projectFiles: ProjectFiles,
    private val state: MutableStateFlow<WorkspaceUiState>,
    private val scope: CoroutineScope,
    private val mirror: WorkspaceExternalMirror,
    private val host: FileActionHost,
) {
    fun createFile(parentDir: String, name: String) {
        val path = joinPath(parentDir, name)
        run(name) { projectFiles.createFile(projectId, path).onSuccess { mirror.write(path, ByteArray(0)) } }
    }

    fun createFolder(parentDir: String, name: String) {
        val path = joinPath(parentDir, name)
        run(name) { projectFiles.createDirectory(projectId, path).onSuccess { mirror.createDirectory(path) } }
    }

    fun rename(node: FileNode, newName: String) {
        scope.launch {
            projectFiles.rename(projectId, node.relativePath, newName)
                .onSuccess { newPath ->
                    // Open tabs still point at the old path (every tab under it, for a folder);
                    // retarget them so saving does not recreate the file under its old name.
                    host.followRename(node.relativePath, newPath)
                    host.refreshTree()
                    mirror.delete(node.relativePath)
                    mirror.path(newPath)
                }
                .onFailure { cause -> host.setStatus(cause.message ?: "Could not rename ${node.name}") }
        }
    }

    fun delete(node: FileNode) {
        scope.launch {
            projectFiles.delete(projectId, node.relativePath)
                .onSuccess {
                    host.closeTab(node.relativePath)
                    host.forget(node.relativePath)
                    host.refreshTree()
                    host.setStatus("Deleted ${node.name}")
                    mirror.delete(node.relativePath)
                }
                .onFailure { cause -> host.setStatus(cause.message ?: "Could not delete ${node.name}") }
        }
    }

    fun copyToClipboard(node: FileNode, cut: Boolean) {
        state.update { it.copy(clipboard = FileClipboard(node.relativePath, cut)) }
        host.setStatus(if (cut) "Cut ${node.name}" else "Copied ${node.name}")
    }

    fun paste(targetDir: String) {
        val clipboard = state.value.clipboard ?: return
        scope.launch {
            val result = if (clipboard.isCut) {
                projectFiles.move(projectId, clipboard.relativePath, targetDir)
            } else {
                projectFiles.copy(projectId, clipboard.relativePath, targetDir)
            }
            result
                .onSuccess { newPath ->
                    if (clipboard.isCut) {
                        state.update { it.copy(clipboard = null) }
                        host.followRename(clipboard.relativePath, newPath)
                    }
                    host.refreshTree()
                    if (clipboard.isCut) mirror.delete(clipboard.relativePath)
                    mirror.path(newPath)
                }
                .onFailure { cause -> host.setStatus(cause.message ?: "Paste failed") }
        }
    }

    private fun run(name: String, action: suspend () -> Result<Unit>) {
        if (name.isBlank()) return
        scope.launch {
            action()
                .onSuccess { host.refreshTree() }
                .onFailure { cause -> host.setStatus(cause.message ?: "Could not create $name") }
        }
    }

    private fun joinPath(parentDir: String, name: String): String =
        if (parentDir.isEmpty()) name else "$parentDir/$name"
}
