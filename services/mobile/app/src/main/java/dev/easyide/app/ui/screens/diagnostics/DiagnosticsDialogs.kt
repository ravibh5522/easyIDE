package dev.easyide.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.Tone

/** A crash headline is a line or two of the exception; the rest is in the saved report. */
private const val HEADLINE_MAX_LINES = 4

/**
 * Asks before anything is deleted, and says how much it will free. With nothing to free there is no
 * confirm action, only Cancel, rather than an action that does nothing.
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
    val confirm = if (pending.bytes > 0) KitAction(stringResource(R.string.diag_confirm_action, freed), onConfirm) else null
    KitDialog(
        title = title,
        onDismiss = onCancel,
        confirm = confirm,
        dismiss = KitAction(stringResource(R.string.action_cancel), onCancel),
        tone = Tone.Danger,
    ) {
        ProseText(if (confirm != null) body else stringResource(R.string.diag_confirm_nothing))
    }
}

/**
 * Shown on the first launch after a crash. [reportSummary] is the one-line exception headline of the
 * saved report. [onReopenLastProject] is null when there is no project to reopen; Share is then the
 * dialog's confirm action, otherwise it sits in the content so the dialog keeps two actions.
 */
@Composable
fun CrashRecoveryDialog(
    reportSummary: String,
    onReopenLastProject: (() -> Unit)?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val share = KitAction(stringResource(R.string.diag_crash_share), onShare)
    val reopen = onReopenLastProject?.let { KitAction(stringResource(R.string.diag_crash_reopen), it) }
    KitDialog(
        title = stringResource(R.string.diag_crash_title),
        onDismiss = onDismiss,
        confirm = reopen ?: share,
        dismiss = KitAction(stringResource(R.string.diag_crash_dismiss), onDismiss),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Kit.space.m)) {
            ProseText(stringResource(R.string.diag_crash_body))
            MonoText(reportSummary, muted = false, tone = Tone.Danger, maxLines = HEADLINE_MAX_LINES)
            if (reopen != null) KitButton(share.label, share.onClick, Modifier.fillMaxWidth(), KitButtonStyle.Secondary)
        }
    }
}
