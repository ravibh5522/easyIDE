package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog

/**
 * Save / Discard / Cancel for an action that would drop unsaved buffers -
 * leaving the workspace or closing a dirty tab. Without it one tap silently
 * loses edits, since nothing else backs the buffer up. Discard is the danger
 * action, kept out of the dialog's confirm slot so it is never the default.
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
    KitDialog(
        title = if (fileName != null) {
            stringResource(R.string.unsaved_title_one, fileName)
        } else {
            pluralStringResource(R.plurals.unsaved_title_many, fileCount, fileCount)
        },
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(if (fileName != null) R.string.unsaved_save else R.string.unsaved_save_all), onSave),
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        DialogText(stringResource(R.string.unsaved_body))
        KitButton(stringResource(R.string.unsaved_discard), onDiscard, Modifier.padding(top = Kit.space.s), KitButtonStyle.Danger)
    }
}
