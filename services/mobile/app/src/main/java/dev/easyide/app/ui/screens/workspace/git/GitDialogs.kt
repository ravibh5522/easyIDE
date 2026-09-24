package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing

/** The question behind [GitConfirm]: what will happen, and a confirm button named for the action. */
@Composable
internal fun GitConfirmDialog(confirm: GitConfirm, onAnswer: (Boolean) -> Unit) {
    val (title, body, action) = when (confirm) {
        is GitConfirm.DiscardFiles -> Triple(
            stringResource(R.string.git_confirm_discard_title),
            pluralStringResource(R.plurals.git_confirm_discard_body, confirm.paths.size, confirm.paths.size),
            stringResource(R.string.git_discard),
        )
        is GitConfirm.DiscardHunk -> Triple(
            stringResource(R.string.git_confirm_discard_hunk_title),
            stringResource(R.string.git_confirm_discard_hunk_body, confirm.path),
            stringResource(R.string.git_discard),
        )
        is GitConfirm.DeleteBranch -> if (confirm.unmerged) {
            Triple(
                stringResource(R.string.git_confirm_delete_unmerged_title, confirm.name),
                stringResource(R.string.git_confirm_delete_unmerged_body),
                stringResource(R.string.git_delete_anyway),
            )
        } else {
            Triple(
                stringResource(R.string.git_confirm_delete_title, confirm.name),
                stringResource(R.string.git_confirm_delete_body),
                stringResource(R.string.git_delete),
            )
        }
        is GitConfirm.SwitchDirty -> Triple(
            stringResource(R.string.git_confirm_switch_title),
            stringResource(R.string.git_confirm_switch_body, confirm.target),
            stringResource(R.string.git_stash_and_switch),
        )
        is GitConfirm.DropStash -> Triple(
            stringResource(R.string.git_confirm_drop_stash_title),
            stringResource(R.string.git_confirm_drop_stash_body),
            stringResource(R.string.git_stash_drop),
        )
        GitConfirm.AmendPushed -> Triple(
            stringResource(R.string.git_confirm_amend_title),
            stringResource(R.string.git_confirm_amend_body),
            stringResource(R.string.git_amend_anyway),
        )
        is GitConfirm.ForcePush -> Triple(
            stringResource(R.string.git_confirm_force_title),
            stringResource(R.string.git_confirm_force_body, confirm.target),
            stringResource(R.string.git_force_push),
        )
        is GitConfirm.RemoveRemote -> Triple(
            stringResource(R.string.git_confirm_remove_remote_title, confirm.name),
            stringResource(R.string.git_confirm_remove_remote_body),
            stringResource(R.string.git_remove),
        )
    }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(action) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.git_cancel)) } },
    )
}

/** A one-field prompt (branch rename); [isValid] gates the confirm button. */
@Composable
internal fun GitNameDialog(
    title: String,
    initial: String,
    label: String,
    confirmLabel: String,
    isValid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                isError = text.isNotBlank() && !isValid(text),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            )
        },
        confirmButton = {
            TextButton(enabled = isValid(text.trim()), onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_cancel)) } },
    )
}
