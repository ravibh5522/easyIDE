package dev.easyide.app.ui.screens.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.screens.settings.contentWidth
import dev.easyide.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val EXPORT_MIME = "text/plain"

/**
 * Diagnostics: what the sandbox runtime, the device and the disk look like right now, what
 * went wrong recently, and the means to clear space or hand a log to a maintainer. Everything
 * is measured on open and on refresh; hashing an image archive is the one thing done only on
 * request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.refresh() }

    val messageText = message?.let { messageFor(it) }
    LaunchedEffect(messageText) {
        val text = messageText ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.onMessageShown()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri ->
        uri?.let(viewModel::exportLogs)
    }
    val actions = DiagnosticsActions(
        onVerifyChecksum = viewModel::verifyChecksum,
        onCleanup = viewModel::requestCleanup,
        onExport = { exportLauncher.launch(context.getString(R.string.diag_export_file_name, LocalDate.now())) },
        onShare = { scope.launch { context.startActivity(shareChooser(context, viewModel.shareText())) } },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diag_refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.loading) item(key = "loading") { LoadingIndicator() }
            state.report?.let { report ->
                sandboxSection(report, state.checksums, state.busy, actions)
                runtimeSection(report)
                appSection(report.build)
                storageSection(report.storage, state.busy, actions)
                problemsSection(report.lastCrash, report.recentProblems)
                logsSection(enabled = !state.busy, actions = actions)
            }
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

/** An indeterminate bar is an endless animation; with motion off the label alone says it is working. */
@Composable
private fun LoadingIndicator() {
    Column(modifier = Modifier.contentWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        if (LocalMotionEnabled.current) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.diag_loading))
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
