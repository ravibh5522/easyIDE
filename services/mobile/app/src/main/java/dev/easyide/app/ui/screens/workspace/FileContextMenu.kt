package dev.easyide.app.ui.screens.workspace

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.easyide.sandbox.files.FileNode

/** What the explorer's long-press menu can do to a node. */
enum class FileAction {
    NEW_FILE,
    NEW_FOLDER,
    COPY,
    CUT,
    PASTE,
    RENAME,
    DELETE,
    COPY_PATH,
    COPY_RELATIVE_PATH,
}

/**
 * Long-press menu for a tree node.
 *
 * New file/folder appear only for directories (that is where they would be
 * created), and paste only when something is on the clipboard - a menu full of
 * entries that silently do nothing is worse than a short one.
 */
@Composable
fun FileContextMenu(
    node: FileNode?,
    canPaste: Boolean,
    onAction: (FileAction, FileNode) -> Unit,
    onDismiss: () -> Unit,
) {
    if (node == null) return

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (node.isDirectory) {
            MenuItem("New file") { onAction(FileAction.NEW_FILE, node) }
            MenuItem("New folder") { onAction(FileAction.NEW_FOLDER, node) }
            HorizontalDivider()
        }

        MenuItem("Copy") { onAction(FileAction.COPY, node) }
        MenuItem("Cut") { onAction(FileAction.CUT, node) }
        if (canPaste && node.isDirectory) {
            MenuItem("Paste") { onAction(FileAction.PASTE, node) }
        }
        HorizontalDivider()

        MenuItem("Rename") { onAction(FileAction.RENAME, node) }
        MenuItem("Delete") { onAction(FileAction.DELETE, node) }
        HorizontalDivider()

        MenuItem("Copy path") { onAction(FileAction.COPY_PATH, node) }
        MenuItem("Copy relative path") { onAction(FileAction.COPY_RELATIVE_PATH, node) }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/** Shared prompt for the actions that need a name (new file/folder, rename). */
@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Deletion is irreversible here - there is no trash - so it is confirmed. */
@Composable
fun ConfirmDeleteDialog(
    node: FileNode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${node.name}?") },
        text = {
            Text(
                if (node.isDirectory) {
                    "This deletes the folder and everything inside it. This cannot be undone."
                } else {
                    "This cannot be undone."
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
