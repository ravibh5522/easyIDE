package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import dev.easyide.sandbox.files.FileNode

/** Which naming dialog is open, if any. */
internal sealed interface PendingPrompt {
    data class NewFile(val parentDir: String) : PendingPrompt
    data class NewFolder(val parentDir: String) : PendingPrompt
    data class Rename(val node: FileNode) : PendingPrompt
    data class Delete(val node: FileNode) : PendingPrompt
    data class RenameTerminal(val tabId: String, val currentTitle: String) : PendingPrompt
    data class CloseDirtyTab(val tab: EditorTab) : PendingPrompt
    data class LeaveWithUnsaved(val dirtyTabs: List<EditorTab>) : PendingPrompt
}

@Composable
internal fun PromptDialogs(
    prompt: PendingPrompt?,
    callbacks: WorkspaceCallbacks,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        null -> Unit

        is PendingPrompt.NewFile -> NameInputDialog(
            title = "New file",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name -> callbacks.onCreateFile(prompt.parentDir, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.NewFolder -> NameInputDialog(
            title = "New folder",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name -> callbacks.onCreateFolder(prompt.parentDir, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.Rename -> NameInputDialog(
            title = "Rename",
            initialValue = prompt.node.name,
            confirmLabel = "Rename",
            onConfirm = { name -> callbacks.onRename(prompt.node, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.Delete -> ConfirmDeleteDialog(
            node = prompt.node,
            onConfirm = { callbacks.onDelete(prompt.node); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.RenameTerminal -> NameInputDialog(
            title = "Rename terminal",
            initialValue = prompt.currentTitle,
            confirmLabel = "Rename",
            onConfirm = { name -> callbacks.onRenameTerminal(prompt.tabId, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.CloseDirtyTab -> {
            val path = prompt.tab.relativePath
            UnsavedChangesDialog(
                fileName = prompt.tab.name,
                fileCount = 1,
                onSave = { onDismiss(); callbacks.onSaveTabs(listOf(path)) { callbacks.onTabClosed(path) } },
                onDiscard = { onDismiss(); callbacks.onTabClosed(path) },
                onDismiss = onDismiss,
            )
        }

        is PendingPrompt.LeaveWithUnsaved -> UnsavedChangesDialog(
            fileName = prompt.dirtyTabs.singleOrNull()?.name,
            fileCount = prompt.dirtyTabs.size,
            onSave = { onDismiss(); callbacks.onSaveTabs(prompt.dirtyTabs.map { it.relativePath }, callbacks.onBack) },
            onDiscard = { onDismiss(); callbacks.onBack() },
            onDismiss = onDismiss,
        )
    }
}
