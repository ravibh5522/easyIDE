package dev.easyide.app.ui.screens.diagnostics

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.BuildInfo
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.diagnostics.DiagnosticsReport
import dev.easyide.app.diagnostics.EnvironmentDiagnostics
import dev.easyide.app.diagnostics.ProotStatus
import dev.easyide.app.diagnostics.StorageUsage
import dev.easyide.app.ui.components.tone
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import androidx.compose.runtime.Composable

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
    if (report.environments.isEmpty()) {
        item(key = "sandbox-empty") {
            KitSection(stringResource(R.string.diag_section_sandbox)) { KitRow(stringResource(R.string.diag_sandbox_empty)) }
        }
    }
    items(report.environments.size, key = { "env-${report.environments[it].id}" }) { index ->
        val env = report.environments[index]
        EnvironmentSection(env, checksums[env.id], busy, actions)
    }
}

@Composable
private fun EnvironmentSection(
    env: EnvironmentDiagnostics,
    progress: ChecksumProgress?,
    busy: Boolean,
    actions: DiagnosticsActions,
) {
    val status = (progress as? ChecksumProgress.Done)?.status ?: env.checksum
    val checking = progress == ChecksumProgress.Running
    KitSection(stringResource(R.string.diag_env_section, env.label)) {
        InfoRow(stringResource(R.string.diag_env_backend), stringResource(backendText(env.backend)))
        KitRow(
            title = stringResource(R.string.diag_env_state),
            trailing = { KitTag(stringResource(stateText(env.state)), tone = env.state.tone()) },
        )
        env.failureReason?.let { KitBanner(it, tone = Tone.Danger) }
        InfoRow(stringResource(R.string.diag_env_image), env.imageLabel)
        InfoRow(stringResource(R.string.diag_env_rootfs), env.rootfsVersion ?: stringResource(R.string.diag_unknown))
        KitRow(
            title = stringResource(R.string.diag_env_checksum),
            subtitle = stringResource(if (checking) R.string.diag_checksum_checking else checksumText(status)),
            trailing = checksumTag(status)?.takeIf { !checking }?.let { tag -> { KitTag(stringResource(tag), tone = checksumTone(status) ?: Tone.Neutral) } },
        )
        InfoRow(stringResource(R.string.diag_env_disk), bytesText(env.diskBytes))
        ActionRow {
            if (status.canVerify) {
                KitButton(stringResource(R.string.diag_verify_checksum), { actions.onVerifyChecksum(env.id) }, style = KitButtonStyle.Ghost, enabled = !checking)
            }
            KitButton(
                stringResource(R.string.diag_clear_caches),
                { actions.onCleanup(Cleanup.Target.EnvironmentPackageCache(env.id)) },
                style = KitButtonStyle.Ghost,
                enabled = !busy,
            )
        }
    }
}

internal fun LazyListScope.runtimeSection(report: DiagnosticsReport) {
    item(key = "runtime") {
        KitSection(stringResource(R.string.diag_section_runtime)) {
            val proot = report.proot
            when (proot) {
                is ProotStatus.Found -> InfoRow(stringResource(R.string.diag_runtime_proot), proot.version)
                is ProotStatus.Unavailable -> InfoRow(
                    stringResource(R.string.diag_runtime_proot),
                    stringResource(R.string.diag_proot_unavailable, prootFailureText(proot)),
                    tone = Tone.Danger,
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
    item(key = "app") {
        KitSection(stringResource(R.string.diag_section_app)) {
            InfoRow(stringResource(R.string.diag_app_version), stringResource(R.string.diag_app_version_value, build.versionName, build.versionCode))
            InfoRow(stringResource(R.string.diag_app_build), build.channel)
            InfoRow(stringResource(R.string.diag_app_device), stringResource(R.string.diag_app_device_value, build.manufacturer, build.model))
            InfoRow(stringResource(R.string.diag_app_android), stringResource(R.string.diag_app_android_value, build.sdkInt))
        }
    }
}

internal fun LazyListScope.storageSection(storage: StorageUsage, busy: Boolean, actions: DiagnosticsActions) {
    item(key = "storage") {
        KitSection(stringResource(R.string.diag_section_storage)) {
            InfoRow(stringResource(R.string.diag_storage_environments), bytesText(storage.environmentsBytes))
            InfoRow(stringResource(R.string.diag_storage_images), bytesText(storage.imageCacheBytes))
            InfoRow(stringResource(R.string.diag_storage_projects), bytesText(storage.projectsBytes))
            InfoRow(stringResource(R.string.diag_storage_extensions), bytesText(storage.extensionsBytes))
            InfoRow(stringResource(R.string.diag_storage_logs), bytesText(storage.logsBytes))
            InfoRow(stringResource(R.string.diag_storage_sessions), bytesText(storage.sessionBackupsBytes))
            InfoRow(stringResource(R.string.diag_storage_total), bytesText(storage.total))
            ActionRow {
                KitButton(stringResource(R.string.diag_storage_delete_images), { actions.onCleanup(Cleanup.Target.ImageArchives) }, style = KitButtonStyle.Ghost, enabled = !busy)
                KitButton(stringResource(R.string.diag_storage_clear_logs), { actions.onCleanup(Cleanup.Target.Logs) }, style = KitButtonStyle.Ghost, enabled = !busy)
            }
        }
    }
}
