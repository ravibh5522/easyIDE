package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R

/**
 * Save / Discard / Cancel for an action that would drop unsaved buffers -
 * leaving the workspace or closing a dirty tab. Without it one tap silently
 * loses edits, since nothing else backs the buffer up.
 *
 * @param fileName the single file at stake, or null to phrase it as a count.
 */
@Composable
fun UnsavedChangesDialog(
    fileName: String?,
    fileCount: Int,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (fileName != null) {
                    stringResource(R.string.unsaved_title_one, fileName)
                } else {
                    pluralStringResource(R.plurals.unsaved_title_many, fileCount, fileCount)
                },
            )
        },
        text = { Text(stringResource(R.string.unsaved_body)) },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(if (fileName != null) R.string.unsaved_save else R.string.unsaved_save_all))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.unsaved_discard)) }
            }
        },
    )
}
