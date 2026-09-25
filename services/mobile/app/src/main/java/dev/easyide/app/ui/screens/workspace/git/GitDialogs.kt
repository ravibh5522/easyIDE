package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.NAME_KEYBOARD

/** What a [GitConfirm] says: the question, what will happen, the action's name, and whether it loses something. */
private data class ConfirmCopy(val title: String, val body: String, val action: String, val danger: Boolean = true)

/** The question behind [GitConfirm]: what will happen, and a confirm button named for the action. */
@Composable
internal fun GitConfirmDialog(confirm: GitConfirm, onAnswer: (Boolean) -> Unit) {
    val copy = when (confirm) {
        is GitConfirm.DiscardFiles -> ConfirmCopy(
            stringResource(R.string.git_confirm_discard_title),
            pluralStringResource(R.plurals.git_confirm_discard_body, confirm.paths.size, confirm.paths.size),
            stringResource(R.string.git_discard),
        )
        is GitConfirm.DiscardHunk -> ConfirmCopy(
            stringResource(R.string.git_confirm_discard_hunk_title),
            stringResource(R.string.git_confirm_discard_hunk_body, confirm.path),
            stringResource(R.string.git_discard),
        )
        is GitConfirm.DeleteBranch -> if (confirm.unmerged) {
            ConfirmCopy(
                stringResource(R.string.git_confirm_delete_unmerged_title, confirm.name),
                stringResource(R.string.git_confirm_delete_unmerged_body),
                stringResource(R.string.git_delete_anyway),
            )
        } else {
            ConfirmCopy(
                stringResource(R.string.git_confirm_delete_title, confirm.name),
                stringResource(R.string.git_confirm_delete_body),
                stringResource(R.string.git_delete),
            )
        }
        is GitConfirm.SwitchDirty -> ConfirmCopy(
            stringResource(R.string.git_confirm_switch_title),
            stringResource(R.string.git_confirm_switch_body, confirm.target),
            stringResource(R.string.git_stash_and_switch),
            danger = false,
        )
        is GitConfirm.DropStash -> ConfirmCopy(
            stringResource(R.string.git_confirm_drop_stash_title),
            stringResource(R.string.git_confirm_drop_stash_body),
            stringResource(R.string.git_stash_drop),
        )
        GitConfirm.AmendPushed -> ConfirmCopy(
            stringResource(R.string.git_confirm_amend_title),
            stringResource(R.string.git_confirm_amend_body),
            stringResource(R.string.git_amend_anyway),
        )
        is GitConfirm.ForcePush -> ConfirmCopy(
            stringResource(R.string.git_confirm_force_title),
            stringResource(R.string.git_confirm_force_body, confirm.target),
            stringResource(R.string.git_force_push),
        )
        is GitConfirm.RemoveRemote -> ConfirmCopy(
            stringResource(R.string.git_confirm_remove_remote_title, confirm.name),
            stringResource(R.string.git_confirm_remove_remote_body),
            stringResource(R.string.git_remove),
        )
    }
    KitDialog(
        title = copy.title,
        onDismiss = { onAnswer(false) },
        tone = if (copy.danger) Tone.Danger else Tone.Neutral,
        confirm = KitAction(copy.action) { onAnswer(true) },
        dismiss = KitAction(stringResource(R.string.git_cancel)) { onAnswer(false) },
    ) {
        DialogText(copy.body)
    }
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
    KitDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = if (isValid(text.trim())) KitAction(confirmLabel) { onConfirm(text.trim()); onDismiss() } else null,
        dismiss = KitAction(stringResource(R.string.git_cancel), onDismiss),
    ) {
        KitField(
            value = text,
            onValueChange = { text = it },
            label = label,
            // The untouched initial value is not "wrong", it is just not a change yet.
            error = if (text.isNotBlank() && text != initial && !isValid(text.trim())) stringResource(R.string.wp_git_invalid_name) else null,
            mono = true,
            keyboard = NAME_KEYBOARD,
        )
    }
}
