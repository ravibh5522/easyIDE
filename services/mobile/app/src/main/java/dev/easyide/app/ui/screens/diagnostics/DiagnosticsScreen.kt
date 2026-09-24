package dev.easyide.app.ui.screens.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.shell.host.ToastHost
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val EXPORT_MIME = "text/plain"

/**
 * Diagnostics: what the sandbox runtime, the device and the disk look like right now, what went
 * wrong recently, and the means to clear space or hand a log to a maintainer. Everything is measured
 * on open and on refresh; hashing an image archive is the one thing done only on request.
 */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Rows appended after the last section; only debug and canary builds pass any (ui.devtools). */
    extraSections: LazyListScope.() -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.refresh() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri ->
        uri?.let(viewModel::exportLogs)
    }
    val actions = DiagnosticsActions(
        onVerifyChecksum = viewModel::verifyChecksum,
        onCleanup = viewModel::requestCleanup,
        onExport = { exportLauncher.launch(context.getString(R.string.diag_export_file_name, LocalDate.now())) },
        onShare = { scope.launch { context.startActivity(shareChooser(context, viewModel.shareText())) } },
    )

    KitScaffold(
        title = stringResource(R.string.diag_title),
        modifier = modifier,
        onBack = onBack,
        actions = { KitIconButton(Icons.Filled.Refresh, stringResource(R.string.diag_refresh), viewModel::refresh, enabled = !state.loading) },
    ) { inset ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = inset.calculateBottomPadding() + Kit.space.xxl)) {
                if (state.loading) item(key = "loading") { KitProgress(null, Modifier.fillMaxWidth().padding(Kit.space.l)) }
                state.report?.let { report ->
                    sandboxSection(report, state.checksums, state.busy, actions)
                    runtimeSection(report)
                    appSection(report.build)
                    storageSection(report.storage, state.busy, actions)
                    problemsSection(report.lastCrash, report.recentProblems)
                    logsSection(enabled = !state.busy, actions = actions)
                }
                extraSections()
            }
            ToastHost(message?.let { messageFor(it) }, viewModel::onMessageShown, Modifier.align(Alignment.BottomStart).padding(bottom = inset.calculateBottomPadding()))
        }
    }

    state.pendingCleanup?.let { pending ->
        val target = pending.target
        val label = (target as? Cleanup.Target.EnvironmentPackageCache)?.let { t ->
            state.report?.environments?.firstOrNull { it.id == t.environmentId }?.label
        }
        CleanupConfirmDialog(pending, label, onConfirm = viewModel::confirmCleanup, onCancel = viewModel::cancelCleanup)
    }
}

@Composable
private fun messageFor(message: DiagnosticsMessage): String = when (message) {
    is DiagnosticsMessage.CleanupDone ->
        if (message.failedEntries == 0) {
            stringResource(R.string.diag_msg_cleanup_done, bytesText(message.freedBytes))
        } else {
            stringResource(R.string.diag_msg_cleanup_partial, bytesText(message.freedBytes), message.failedEntries)
        }
    DiagnosticsMessage.CleanupFailed -> stringResource(R.string.diag_msg_cleanup_failed)
    DiagnosticsMessage.VerifyFailed -> stringResource(R.string.diag_msg_verify_failed)
    DiagnosticsMessage.ExportDone -> stringResource(R.string.diag_msg_export_done)
    DiagnosticsMessage.ExportFailed -> stringResource(R.string.diag_msg_export_failed)
}
