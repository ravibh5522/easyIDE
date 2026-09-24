package dev.easyide.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.ui.theme.Spacing

/**
 * Asks before anything is deleted, and says how much it will free. With nothing to free the
 * confirm button is disabled and says so, rather than offering an action that does nothing.
 *
 * @param environmentLabel the environment a package-cache cleanup applies to.
 */
@Composable
internal fun CleanupConfirmDialog(
    pending: PendingCleanup,
    environmentLabel: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val freed = bytesText(pending.bytes)
    val (title, body) = when (pending.target) {
        is Cleanup.Target.EnvironmentPackageCache ->
            stringResource(R.string.diag_confirm_title_caches, environmentLabel.orEmpty()) to stringResource(R.string.diag_confirm_body_caches, freed)
        Cleanup.Target.ImageArchives ->
            stringResource(R.string.diag_confirm_title_images) to stringResource(R.string.diag_confirm_body_images, freed)
        Cleanup.Target.Logs ->
            stringResource(R.string.diag_confirm_title_logs) to stringResource(R.string.diag_confirm_body_logs, freed)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = pending.bytes > 0) {
                Text(if (pending.bytes > 0) stringResource(R.string.diag_confirm_action, freed) else stringResource(R.string.diag_confirm_nothing))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Shown on the first launch after a crash. [reportSummary] is the one-line exception headline
 * of the saved report. [onReopenLastProject] is null when there is no project to reopen, and
 * the button is then not shown.
 */
@Composable
fun CrashRecoveryDialog(
    reportSummary: String,
    onReopenLastProject: (() -> Unit)?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_crash_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.diag_crash_body))
                Text(reportSummary, style = monoStyle())
            }
        },
        // Three actions do not fit the two dialog slots side by side on a phone, so they stack.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                onReopenLastProject?.let { reopen ->
                    TextButton(onClick = reopen) { Text(stringResource(R.string.diag_crash_reopen)) }
                }
                TextButton(onClick = onShare) { Text(stringResource(R.string.diag_crash_share)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_crash_dismiss)) }
            }
        },
    )
}
