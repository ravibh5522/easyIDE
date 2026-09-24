package dev.easyide.app.ui.screens.newproject

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.components.label
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.app.ui.components.tone
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.model.SandboxBackend

/**
 * Where this project's files live. App storage is always where they actually run from (see
 * [ExternalFolderSync]); a chosen folder is a live mirror kept alongside it, so the project is also
 * visible from a normal file manager. Two rows, one selected: tapping the first drops the mirror.
 */
@Composable
internal fun StorageSection(
    externalFolderSync: ExternalFolderSync,
    folderName: String?,
    onFolderChosen: (Uri) -> Unit,
    onFolderCleared: () -> Unit,
    onFolderPickFailed: () -> Unit,
) {
    val pickFolder = rememberFolderPicker(externalFolderSync, onFolderChosen, onFolderPickFailed)
    KitSection(stringResource(R.string.new_project_storage_section), Modifier.selectableGroup()) {
        KitRow(
            title = stringResource(R.string.new_project_storage_app_default),
            subtitle = stringResource(R.string.new_project_storage_app_body),
            selected = folderName == null,
            onClick = onFolderCleared,
            id = "storage-app",
        )
        KitRow(
            title = folderName ?: stringResource(R.string.new_project_storage_choose),
            subtitle = stringResource(R.string.new_project_storage_body),
            selected = folderName != null,
            mono = folderName != null,
            onClick = pickFolder,
            id = "storage-folder",
        )
    }
}

/** Reuse is the cheap default and only offered once there is something to reuse; creating is the deliberate choice. */
@Composable
internal fun EnvironmentChoiceSection(uiState: NewProjectUiState, onChoiceChanged: (EnvironmentChoice) -> Unit) {
    KitSection(stringResource(R.string.new_project_environment_section), Modifier.selectableGroup()) {
        if (uiState.canReuse) {
            KitRow(
                title = stringResource(R.string.new_project_reuse),
                subtitle = stringResource(R.string.new_project_reuse_body),
                selected = uiState.choice == EnvironmentChoice.REUSE_EXISTING,
                onClick = { onChoiceChanged(EnvironmentChoice.REUSE_EXISTING) },
                id = "choice-reuse",
            )
        }
        KitRow(
            title = stringResource(R.string.new_project_create_new),
            subtitle = stringResource(R.string.new_project_create_new_body),
            selected = uiState.choice == EnvironmentChoice.CREATE_NEW,
            onClick = { onChoiceChanged(EnvironmentChoice.CREATE_NEW) },
            id = "choice-create",
        )
    }
}

@Composable
internal fun ExistingEnvironments(uiState: NewProjectUiState, onEnvironmentSelected: (String) -> Unit) {
    KitSection(stringResource(R.string.new_project_existing_section), Modifier.selectableGroup()) {
        uiState.availableEnvironments.forEach { environment ->
            KitRow(
                title = environment.label,
                subtitle = environment.backend.label(),
                selected = uiState.selectedEnvironmentId == environment.id,
                onClick = { onEnvironmentSelected(environment.id) },
                trailing = { KitTag(environment.state.label(), tone = environment.state.tone()) },
                id = "environment:${environment.id}",
            )
        }
    }
}

@Composable
internal fun NewEnvironmentForm(
    uiState: NewProjectUiState,
    onNewEnvironmentLabelChanged: (String) -> Unit,
    onBackendSelected: (SandboxBackend) -> Unit,
    onImageSelected: (String) -> Unit,
) {
    val gutter = Modifier.padding(start = Kit.control.hPad, end = Kit.control.hPad, top = Kit.space.m)
    KitField(
        value = uiState.newEnvironmentLabel,
        onValueChange = onNewEnvironmentLabelChanged,
        modifier = gutter.fillMaxWidth(),
        label = stringResource(R.string.new_project_environment_label),
    )
    KitSection(stringResource(R.string.new_project_image_label), Modifier.selectableGroup()) {
        uiState.availableImages.forEach { image ->
            KitRow(
                title = image.label,
                subtitle = image.description,
                selected = uiState.selectedImageId == image.id,
                onClick = { onImageSelected(image.id) },
                trailing = if (presetReady(uiState.availableEnvironments, image.id)) {
                    { KitTag(stringResource(R.string.flow_tag_ready), tone = Tone.Success) }
                } else null,
                id = "preset:${image.id}",
            )
        }
    }
    // Only offered when more than one backend is available, so unrooted devices never see a chroot option they cannot use.
    if (uiState.availableBackends.size > 1) {
        val labels = uiState.availableBackends.associateWith { it.label() }
        Column(gutter, verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            ProseText(stringResource(R.string.new_project_backend_label), muted = true)
            KitChoice(
                options = uiState.availableBackends,
                selected = uiState.selectedBackend,
                label = { backend -> labels.getValue(backend) },
                onSelect = onBackendSelected,
            )
            if (uiState.selectedBackend == SandboxBackend.CHROOT) {
                KitBanner(stringResource(R.string.new_project_backend_chroot_warning), tone = Tone.Warning)
            }
        }
    }
}
