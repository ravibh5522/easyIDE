package dev.easyide.app.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.BuildInfo
import dev.easyide.app.diagnostics.ChecksumStatus
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.diagnostics.CrashSummary
import dev.easyide.app.diagnostics.DiagnosticsReport
import dev.easyide.app.diagnostics.EnvironmentDiagnostics
import dev.easyide.app.diagnostics.LogFormat
import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogLine
import dev.easyide.app.diagnostics.ProotFailure
import dev.easyide.app.diagnostics.ProotStatus
import dev.easyide.app.diagnostics.StorageUsage
import dev.easyide.app.ui.screens.settings.contentWidth
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import java.text.DateFormat
import java.util.Date

/** What the sections can ask of the screen. Grouped so each section takes one parameter, not six lambdas. */
internal class DiagnosticsActions(
    val onVerifyChecksum: (environmentId: String) -> Unit,
    val onCleanup: (Cleanup.Target) -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
)

internal fun LazyListScope.sandboxSection(
    report: DiagnosticsReport,
    checksums: Map<String, ChecksumProgress>,
    busy: Boolean,
    actions: DiagnosticsActions,
) {
    item(key = "sandbox-header") { SectionHeader(stringResource(R.string.diag_section_sandbox)) }
    if (report.environments.isEmpty()) {
        item(key = "sandbox-empty") { SectionCard { Text(stringResource(R.string.diag_sandbox_empty)) } }
    }
    items(report.environments.size, key = { "env-${report.environments[it].id}" }) { index ->
        val env = report.environments[index]
        EnvironmentCard(env, checksums[env.id], busy, actions)
    }
}

@Composable
private fun EnvironmentCard(
    env: EnvironmentDiagnostics,
    progress: ChecksumProgress?,
    busy: Boolean,
    actions: DiagnosticsActions,
) {
    val status = (progress as? ChecksumProgress.Done)?.status ?: env.checksum
    val checking = progress == ChecksumProgress.Running
    SectionCard {
        Text(env.label, style = MaterialTheme.typography.titleMedium)
        InfoRow(stringResource(R.string.diag_env_backend), stringResource(backendText(env.backend)))
        InfoRow(
            stringResource(R.string.diag_env_state),
            stringResource(stateText(env.state)),
            valueColor = if (env.state == EnvironmentState.FAILED) editorColors.error else Color.Unspecified,
        )
        env.failureReason?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall, color = editorColors.error)
        }
        InfoRow(stringResource(R.string.diag_env_image), env.imageLabel)
        InfoRow(stringResource(R.string.diag_env_rootfs), env.rootfsVersion ?: stringResource(R.string.diag_unknown))
        InfoRow(
            stringResource(R.string.diag_env_checksum),
            stringResource(if (checking) R.string.diag_checksum_checking else checksumText(status)),
            valueColor = checksumColor(status),
        )
        InfoRow(stringResource(R.string.diag_env_disk), bytesText(env.diskBytes))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (status.canVerify) {
                TextButton(onClick = { actions.onVerifyChecksum(env.id) }, enabled = !checking) {
                    Text(stringResource(R.string.diag_verify_checksum))
                }
            }
            TextButton(onClick = { actions.onCleanup(Cleanup.Target.EnvironmentPackageCache(env.id)) }, enabled = !busy) {
                Text(stringResource(R.string.diag_clear_caches))
            }
        }
    }
}

internal fun LazyListScope.runtimeSection(report: DiagnosticsReport) {
    item(key = "runtime-header") { SectionHeader(stringResource(R.string.diag_section_runtime)) }
    item(key = "runtime") {
        SectionCard {
            val proot = report.proot
            when (proot) {
                is ProotStatus.Found -> InfoRow(stringResource(R.string.diag_runtime_proot), proot.version)
                is ProotStatus.Unavailable -> InfoRow(
                    stringResource(R.string.diag_runtime_proot),
                    stringResource(R.string.diag_proot_unavailable, prootFailureText(proot)),
                    valueColor = editorColors.error,
                )
            }
            InfoRow(stringResource(R.string.diag_runtime_proot_path), proot.path)
            InfoRow(stringResource(R.string.diag_runtime_kernel), "${report.uname.sysname} ${report.uname.release}")
            InfoRow(stringResource(R.string.diag_runtime_arch), report.uname.machine)
            InfoRow(stringResource(R.string.diag_runtime_abis), report.build.abis.joinToString(", "))
        }
    }
}

internal fun LazyListScope.appSection(build: BuildInfo) {
    item(key = "app-header") { SectionHeader(stringResource(R.string.diag_section_app)) }
    item(key = "app") {
        SectionCard {
            InfoRow(stringResource(R.string.diag_app_version), stringResource(R.string.diag_app_version_value, build.versionName, build.versionCode))
            InfoRow(stringResource(R.string.diag_app_build), build.channel)
            InfoRow(stringResource(R.string.diag_app_device), stringResource(R.string.diag_app_device_value, build.manufacturer, build.model))
            InfoRow(stringResource(R.string.diag_app_android), stringResource(R.string.diag_app_android_value, build.sdkInt))
        }
    }
}

internal fun LazyListScope.storageSection(storage: StorageUsage, busy: Boolean, actions: DiagnosticsActions) {
    item(key = "storage-header") { SectionHeader(stringResource(R.string.diag_section_storage)) }
    item(key = "storage") {
        SectionCard {
            InfoRow(stringResource(R.string.diag_storage_environments), bytesText(storage.environmentsBytes))
            InfoRow(stringResource(R.string.diag_storage_images), bytesText(storage.imageCacheBytes))
            InfoRow(stringResource(R.string.diag_storage_projects), bytesText(storage.projectsBytes))
            InfoRow(stringResource(R.string.diag_storage_extensions), bytesText(storage.extensionsBytes))
            InfoRow(stringResource(R.string.diag_storage_logs), bytesText(storage.logsBytes))
            InfoRow(stringResource(R.string.diag_storage_sessions), bytesText(storage.sessionBackupsBytes))
            InfoRow(stringResource(R.string.diag_storage_total), bytesText(storage.total))
            Column {
                TextButton(onClick = { actions.onCleanup(Cleanup.Target.ImageArchives) }, enabled = !busy) {
                    Text(stringResource(R.string.diag_storage_delete_images))
                }
                TextButton(onClick = { actions.onCleanup(Cleanup.Target.Logs) }, enabled = !busy) {
                    Text(stringResource(R.string.diag_storage_clear_logs))
                }
            }
        }
    }
}

internal fun LazyListScope.problemsSection(crash: CrashSummary?, problems: List<LogLine>) {
    item(key = "problems-header") { SectionHeader(stringResource(R.string.diag_section_problems)) }
    item(key = "problems") {
        SectionCard {
            crash?.let { CrashRow(it) }
            if (problems.isEmpty() && crash == null) Text(stringResource(R.string.diag_problems_none))
            if (problems.isNotEmpty()) {
                Text(stringResource(R.string.diag_last_errors), style = MaterialTheme.typography.titleSmall)
                problems.forEach { ProblemLine(it) }
            }
        }
    }
}

@Composable
private fun CrashRow(crash: CrashSummary) {
    val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crash.atMs))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        InfoRow(stringResource(R.string.diag_last_crash), whenText)
        Text(crash.headline, style = monoStyle(), color = editorColors.error)
        if (crash.pending) {
            Text(stringResource(R.string.diag_last_crash_new), style = MaterialTheme.typography.labelMedium, color = editorColors.warning)
        }
    }
}

@Composable
private fun ProblemLine(line: LogLine) {
    Text(
        text = LogFormat.encode(line),
        style = monoStyle(),
        color = if (line.level == LogLevel.ERROR) editorColors.error else editorColors.warning,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun LazyListScope.logsSection(enabled: Boolean, actions: DiagnosticsActions) {
    item(key = "logs-header") { SectionHeader(stringResource(R.string.diag_section_logs)) }
    item(key = "logs") {
        SectionCard {
            Text(stringResource(R.string.diag_logs_body), style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = actions.onExport, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_export))
                }
                OutlinedButton(onClick = actions.onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_share))
                }
            }
        }
    }
}

private val ChecksumStatus.canVerify: Boolean
    get() = this != ChecksumStatus.ARCHIVE_REMOVED && this != ChecksumStatus.NO_PINNED_ROOTFS

@StringRes
private fun backendText(backend: SandboxBackend): Int = when (backend) {
    SandboxBackend.PROOT -> R.string.diag_backend_proot
    SandboxBackend.CHROOT -> R.string.diag_backend_chroot
}

@StringRes
private fun stateText(state: EnvironmentState): Int = when (state) {
    EnvironmentState.NOT_PROVISIONED -> R.string.diag_state_not_provisioned
    EnvironmentState.PROVISIONING -> R.string.diag_state_provisioning
    EnvironmentState.READY -> R.string.diag_state_ready
    EnvironmentState.FAILED -> R.string.diag_state_failed
}

@StringRes
private fun checksumText(status: ChecksumStatus): Int = when (status) {
    ChecksumStatus.NOT_CHECKED -> R.string.diag_checksum_not_checked
    ChecksumStatus.VERIFIED -> R.string.diag_checksum_verified
    ChecksumStatus.MISMATCH -> R.string.diag_checksum_mismatch
    ChecksumStatus.ARCHIVE_REMOVED -> R.string.diag_checksum_archive_removed
    ChecksumStatus.NO_PINNED_ROOTFS -> R.string.diag_checksum_no_pinned
}

/** Only a real result is coloured: "not checked" and "archive removed" are neither good nor bad. */
@Composable
private fun checksumColor(status: ChecksumStatus): Color = when (status) {
    ChecksumStatus.VERIFIED -> editorColors.success
    ChecksumStatus.MISMATCH -> editorColors.error
    else -> Color.Unspecified
}

@Composable
private fun prootFailureText(status: ProotStatus.Unavailable): String {
    val detail = status.detail.orEmpty()
    return when (status.failure) {
        ProotFailure.MISSING -> stringResource(R.string.diag_proot_missing)
        ProotFailure.TIMED_OUT -> stringResource(R.string.diag_proot_timed_out)
        ProotFailure.EXIT_CODE -> stringResource(R.string.diag_proot_exit_code, detail)
        ProotFailure.NO_OUTPUT -> stringResource(R.string.diag_proot_no_output)
        ProotFailure.START_FAILED -> stringResource(R.string.diag_proot_start_failed, detail)
    }
}
