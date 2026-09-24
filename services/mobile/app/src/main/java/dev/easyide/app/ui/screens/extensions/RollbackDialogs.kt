package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog

/**
 * Rollback (registry-and-install.md sec 10): a confirmation naming both versions, then, if
 * the retained version declares capabilities never approved for it, the same capability
 * prompts the install sheet shows. Declining either changes nothing.
 */
@Composable
internal fun RollbackDialogs(rollback: RollbackState, viewModel: ExtensionsViewModel) {
    val cancel = KitAction(stringResource(R.string.ext_prompt_cancel), viewModel::dismissRollback)
    when (rollback) {
        RollbackState.Idle -> Unit
        is RollbackState.Confirm -> {
            val row = rollback.row
            val current = row.loaded?.descriptor?.version?.toString() ?: row.pkg.directory.name
            KitDialog(
                title = stringResource(R.string.ext_rollback_confirm_title, row.loaded?.descriptor?.displayName ?: row.id, rollback.version),
                onDismiss = viewModel::dismissRollback,
                confirm = KitAction(stringResource(R.string.ext_rollback_action)) { viewModel.confirmRollback(row) },
                dismiss = cancel,
            ) {
                DialogText(stringResource(R.string.ext_rollback_confirm_body, current))
            }
        }
        is RollbackState.Approve -> {
            val d = rollback.descriptor
            KitDialog(
                title = stringResource(R.string.ext_rollback_approve_title, d.displayName, d.version.toString()),
                onDismiss = viewModel::dismissRollback,
                confirm = KitAction(stringResource(R.string.ext_rollback_approve)) { viewModel.confirmRollback(rollback.row, rollback.capabilities) },
                dismiss = cancel,
            ) {
                DialogText(stringResource(R.string.ext_rollback_approve_body))
                d.capabilities.items.filter { it.id in rollback.capabilities }.sortedBy { it.id }
                    .forEach { DialogText(capabilityPrompt(it, rollback.row.pkg.envId)) }
                DialogText(stringResource(R.string.ext_install_not_isolated), muted = true, modifier = Modifier.padding(top = Kit.space.s))
            }
        }
        is RollbackState.Refused -> ProblemDialog(stringResource(R.string.ext_rollback_refused), rollback.problems, viewModel::dismissRollback)
    }
}
