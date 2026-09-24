package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.model.EnvironmentState

/**
 * The Sandbox page: the environments and what depends on them. Environments are listed here because
 * deleting one is a cross-project action - the usage count per row explains an "in use" refusal.
 * Storage and environments are device-wide, not layered settings. Copy never claims isolation.
 */
@Composable
internal fun SandboxPage(viewModel: SettingsViewModel, ui: SettingsUiState) {
    if (ui.environments.isEmpty()) {
        KitEmptyState(EmptyArt.Environment, stringResource(R.string.settings_environments_empty))
        return
    }
    KitSection(stringResource(R.string.settings_sandbox_section)) {
        ui.environments.forEach { item ->
            EnvironmentRow(
                item = item,
                isDefault = ui.defaultEnvironmentId == item.environment.id,
                onSetDefault = { viewModel.onDefaultEnvironmentSelected(item.environment.id) },
                onDelete = { viewModel.onDeleteEnvironment(item.environment.id) },
            )
        }
    }
}

@Composable
private fun EnvironmentRow(item: EnvironmentListItem, isDefault: Boolean, onSetDefault: () -> Unit, onDelete: () -> Unit) {
    val used = if (item.usedByProjectCount == 0) {
        stringResource(R.string.settings_environment_unused)
    } else {
        stringResource(R.string.settings_environment_used_by, item.usedByProjectCount)
    }
    val detail = "${item.environment.backend.name.lowercase()} - $used"
    KitRow(
        title = item.environment.label,
        subtitle = if (isDefault) "$detail - ${stringResource(R.string.settings_environment_is_default)}" else detail,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                KitTag(stringResource(stateLabel(item.environment.state)), tone = stateTone(item.environment.state))
                KitIconButton(
                    if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    stringResource(R.string.settings_set_default_environment), onSetDefault,
                    tone = if (isDefault) Tone.Accent else Tone.Neutral,
                )
                KitIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.settings_delete_environment), onDelete)
            }
        },
        id = "environment:${item.environment.id}",
    )
}

private fun stateLabel(state: EnvironmentState): Int = when (state) {
    EnvironmentState.READY -> R.string.settings_env_state_ready
    EnvironmentState.PROVISIONING -> R.string.settings_env_state_installing
    EnvironmentState.FAILED -> R.string.settings_env_state_failed
    EnvironmentState.NOT_PROVISIONED -> R.string.settings_env_state_not_installed
}

private fun stateTone(state: EnvironmentState): Tone = when (state) {
    EnvironmentState.READY -> Tone.Success
    EnvironmentState.PROVISIONING -> Tone.Info
    EnvironmentState.FAILED -> Tone.Danger
    EnvironmentState.NOT_PROVISIONED -> Tone.Neutral
}

/**
 * The default location suggested when creating a new project. A folder here does not move existing
 * projects - it only pre-fills the choice on the next "New project" screen, which can always be
 * overridden per project.
 */
@Composable
internal fun StorageSection(viewModel: SettingsViewModel, ui: SettingsUiState, host: SettingsHost) {
    val pickFolder = rememberFolderPicker(
        externalFolderSync = host.externalFolderSync,
        onPicked = { uri -> viewModel.onDefaultProjectsFolderChosen(uri.toString()) },
        onFailed = viewModel::onDefaultProjectsFolderPickFailed,
    )
    val folder = ui.defaultProjectsFolderName
    KitSection(stringResource(R.string.settings_storage_section)) {
        KitRow(
            title = folder ?: stringResource(R.string.settings_storage_app_default),
            subtitle = stringResource(if (folder != null) R.string.settings_storage_folder_chosen_body else R.string.settings_storage_app_default_body),
            mono = folder != null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null) {
                        KitButton(stringResource(R.string.settings_storage_clear), { viewModel.onDefaultProjectsFolderChosen(null) }, style = KitButtonStyle.Ghost)
                    }
                    KitButton(stringResource(R.string.settings_storage_choose_folder), pickFolder, style = KitButtonStyle.Secondary)
                }
            },
            id = "storage-folder",
        )
    }
}
