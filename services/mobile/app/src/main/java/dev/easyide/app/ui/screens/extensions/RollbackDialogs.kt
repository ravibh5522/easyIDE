package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R

/**
 * Rollback (registry-and-install.md sec 10): a confirmation naming both versions, then, if
 * the retained version declares capabilities never approved for it, the same capability
 * prompts the install sheet shows. Declining either changes nothing.
 */
@Composable
fun RollbackDialogs(rollback: RollbackState, viewModel: ExtensionsViewModel) {
    when (rollback) {
        RollbackState.Idle -> Unit
        is RollbackState.Confirm -> {
            val row = rollback.row
            val current = row.loaded?.descriptor?.version?.toString() ?: row.pkg.directory.name
            AlertDialog(
                onDismissRequest = viewModel::dismissRollback,
                title = { Text(stringResource(R.string.ext_rollback_confirm_title, row.loaded?.descriptor?.displayName ?: row.id, rollback.version)) },
                text = { Text(stringResource(R.string.ext_rollback_confirm_body, current)) },
                confirmButton = { TextButton(onClick = { viewModel.confirmRollback(row) }) { Text(stringResource(R.string.ext_rollback_action)) } },
                dismissButton = { TextButton(onClick = viewModel::dismissRollback) { Text(stringResource(R.string.ext_prompt_cancel)) } },
            )
        }
        is RollbackState.Approve -> {
            val d = rollback.descriptor
            AlertDialog(
                onDismissRequest = viewModel::dismissRollback,
                title = { Text(stringResource(R.string.ext_rollback_approve_title, d.displayName, d.version.toString())) },
                text = {
                    Column(modifier = Modifier.heightIn(max = SHEET_MAX_DP.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.ext_rollback_approve_body))
                        d.capabilities.items.filter { it.id in rollback.capabilities }.sortedBy { it.id }
                            .forEach { Text("- ${capabilityPrompt(it, rollback.row.pkg.envId)}") }
                        Text(stringResource(R.string.ext_install_not_isolated), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmRollback(rollback.row, rollback.capabilities) }) {
                        Text(stringResource(R.string.ext_rollback_approve))
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissRollback) { Text(stringResource(R.string.ext_prompt_cancel)) } },
            )
        }
        is RollbackState.Refused -> ProblemDialog(stringResource(R.string.ext_rollback_refused), rollback.problems, viewModel::dismissRollback)
    }
}
