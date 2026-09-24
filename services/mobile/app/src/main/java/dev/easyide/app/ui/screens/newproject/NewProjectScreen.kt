package dev.easyide.app.ui.screens.newproject

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.FlowFrame
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.model.SandboxBackend

/**
 * New project flow: name, storage, environment. The environment step is the point of
 * docs/decision/0005-sandbox-environment-sharing-model.md: reusing an existing environment is the
 * cheap default, creating a new one is the deliberate choice, and both are presented as such.
 * Create is the one primary action, pinned in [FlowFrame]'s footer.
 */
@Composable
fun NewProjectScreen(
    uiState: NewProjectUiState,
    externalFolderSync: ExternalFolderSync,
    onProjectNameChanged: (String) -> Unit,
    onChoiceChanged: (EnvironmentChoice) -> Unit,
    onEnvironmentSelected: (String) -> Unit,
    onNewEnvironmentLabelChanged: (String) -> Unit,
    onBackendSelected: (SandboxBackend) -> Unit,
    onImageSelected: (String) -> Unit,
    onExternalFolderChosen: (Uri) -> Unit,
    onExternalFolderCleared: () -> Unit,
    onExternalFolderPickFailed: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = uiState.error
    FlowFrame(
        title = stringResource(R.string.new_project_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                if (error != null && !error.isAboutName()) KitBanner(error.text(), tone = Tone.Danger)
                KitButton(
                    stringResource(R.string.action_create),
                    onSubmit,
                    Modifier.fillMaxWidth(),
                    loading = uiState.isSubmitting,
                    enabled = uiState.canSubmit,
                )
            }
        },
    ) {
        KitField(
            value = uiState.projectName,
            onValueChange = onProjectNameChanged,
            modifier = Modifier.fillMaxWidth().padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l),
            label = stringResource(R.string.new_project_name_label),
            error = error?.takeIf { it.isAboutName() }?.text(),
        )
        StorageSection(externalFolderSync, uiState.externalFolderName, onExternalFolderChosen, onExternalFolderCleared, onExternalFolderPickFailed)
        EnvironmentChoiceSection(uiState, onChoiceChanged)
        when (uiState.choice) {
            EnvironmentChoice.REUSE_EXISTING -> ExistingEnvironments(uiState, onEnvironmentSelected)
            EnvironmentChoice.CREATE_NEW ->
                NewEnvironmentForm(uiState, onNewEnvironmentLabelChanged, onBackendSelected, onImageSelected)
        }
        Spacer(Modifier.height(Kit.space.l))
    }
}

@Composable
private fun NewProjectError.text(): String = when (this) {
    NewProjectError.NameTaken -> stringResource(R.string.home_error_name_taken)
    NewProjectError.NameBlank -> stringResource(R.string.home_error_name_blank)
    NewProjectError.FolderPickFailed -> stringResource(R.string.new_project_error_folder)
    is NewProjectError.Other -> detail?.let { stringResource(R.string.home_error_other_detail, it) }
        ?: stringResource(R.string.new_project_error_generic)
}
