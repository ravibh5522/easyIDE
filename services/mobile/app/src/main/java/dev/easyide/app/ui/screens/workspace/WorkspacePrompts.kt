package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.sandbox.files.FileNode

/** Which dialog is open, if any. Names for files and folders are typed into the tree ([InlineEdit]), not asked for here. */
internal sealed interface PendingPrompt {
    data class Delete(val node: FileNode) : PendingPrompt
    data class RenameTerminal(val tabId: String, val currentTitle: String) : PendingPrompt
    data class CloseDirtyTab(val tab: EditorTab) : PendingPrompt
    data class CloseWithUnsaved(val dirtyTabs: List<EditorTab>) : PendingPrompt
}

@Composable
internal fun PromptDialogs(
    prompt: PendingPrompt?,
    callbacks: WorkspaceCallbacks,
    onCloseProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        null -> Unit

        is PendingPrompt.Delete -> ConfirmDeleteDialog(
            node = prompt.node,
            onConfirm = { callbacks.onDelete(prompt.node); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.RenameTerminal -> NameInputDialog(
            title = stringResource(R.string.wp_rename_terminal),
            initialValue = prompt.currentTitle,
            confirmLabel = stringResource(R.string.wp_rename),
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

        is PendingPrompt.CloseWithUnsaved -> UnsavedChangesDialog(
            fileName = prompt.dirtyTabs.singleOrNull()?.name,
            fileCount = prompt.dirtyTabs.size,
            onSave = { onDismiss(); callbacks.onSaveTabs(prompt.dirtyTabs.map { it.relativePath }, onCloseProject) },
            onDiscard = { onDismiss(); onCloseProject() },
            onDismiss = onDismiss,
        )
    }
}
