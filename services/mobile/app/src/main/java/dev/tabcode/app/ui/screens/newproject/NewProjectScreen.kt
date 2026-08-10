package dev.tabcode.app.ui.screens.newproject

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tabcode.app.R
import dev.tabcode.app.ui.components.ChoiceCard
import dev.tabcode.app.ui.components.EnvironmentBadge
import dev.tabcode.app.ui.components.rememberFolderPicker
import dev.tabcode.app.ui.foundation.motionSpec
import dev.tabcode.sandbox.external.ExternalFolderSync
import dev.tabcode.sandbox.model.SandboxBackend

/**
 * New project flow. The environment step is the point of
 * docs/decision/0005-sandbox-environment-sharing-model.md: reusing an existing
 * environment is the cheap default, creating a new one is the deliberate
 * choice, and both are presented as such.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_project_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CONTENT_PADDING_DP.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // Capped so a long form does not stretch into an unreadable
                // single line across an expanded tablet.
                modifier = Modifier.widthIn(max = MAX_FORM_WIDTH_DP.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = uiState.projectName,
                    onValueChange = onProjectNameChanged,
                    label = { Text(stringResource(R.string.new_project_name_label)) },
                    singleLine = true,
                    isError = uiState.errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                StorageFolderPicker(
                    externalFolderSync = externalFolderSync,
                    folderName = uiState.externalFolderName,
                    onFolderChosen = onExternalFolderChosen,
                    onFolderCleared = onExternalFolderCleared,
                    onFolderPickFailed = onExternalFolderPickFailed,
                )

                Text(
                    text = stringResource(R.string.new_project_environment_section),
                    style = MaterialTheme.typography.titleSmall,
                )

                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.canReuse) {
                        ChoiceCard(
                            selected = uiState.choice == EnvironmentChoice.REUSE_EXISTING,
                            title = stringResource(R.string.new_project_reuse),
                            body = stringResource(R.string.new_project_reuse_body),
                            onClick = { onChoiceChanged(EnvironmentChoice.REUSE_EXISTING) },
                        )
                    }
                    ChoiceCard(
                        selected = uiState.choice == EnvironmentChoice.CREATE_NEW,
                        title = stringResource(R.string.new_project_create_new),
                        body = stringResource(R.string.new_project_create_new_body),
                        onClick = { onChoiceChanged(EnvironmentChoice.CREATE_NEW) },
                    )
                }

                // animateContentSize keeps the swap between the two sub-forms
                // from jumping the layout.
                Column(modifier = Modifier.animateContentSize(motionSpec())) {
                    when (uiState.choice) {
                        EnvironmentChoice.REUSE_EXISTING -> ExistingEnvironmentPicker(
                            uiState = uiState,
                            onEnvironmentSelected = onEnvironmentSelected,
                        )

                        EnvironmentChoice.CREATE_NEW -> NewEnvironmentForm(
                            uiState = uiState,
                            onNewEnvironmentLabelChanged = onNewEnvironmentLabelChanged,
                            onBackendSelected = onBackendSelected,
                            onImageSelected = onImageSelected,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(motionSpec()),
                    exit = fadeOut(motionSpec()),
                ) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = onSubmit,
                    enabled = uiState.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = PROGRESS_STROKE_DP.dp,
                        )
                    }
                    Text(stringResource(R.string.action_create))
                }
            }
        }
    }
}

/**
 * Where this project's files live. App storage is always where they actually
 * run from (see [ExternalFolderSync]); a chosen folder is a live mirror kept
 * alongside it, so the project is also visible from a normal file manager.
 */
@Composable
private fun StorageFolderPicker(
    externalFolderSync: ExternalFolderSync,
    folderName: String?,
    onFolderChosen: (Uri) -> Unit,
    onFolderCleared: () -> Unit,
    onFolderPickFailed: () -> Unit,
) {
    val pickFolder = rememberFolderPicker(
        externalFolderSync = externalFolderSync,
        onPicked = onFolderChosen,
        onFailed = onFolderPickFailed,
    )

    ListItem(
        headlineContent = {
            Text(folderName ?: stringResource(R.string.new_project_storage_app_default))
        },
        supportingContent = {
            Text(stringResource(R.string.new_project_storage_body))
        },
        leadingContent = { Icon(imageVector = Icons.Filled.Folder, contentDescription = null) },
        trailingContent = {
            Row {
                if (folderName != null) {
                    TextButton(onClick = onFolderCleared) {
                        Text(stringResource(R.string.settings_storage_clear))
                    }
                }
                TextButton(onClick = pickFolder) {
                    Text(stringResource(R.string.settings_storage_choose_folder))
                }
            }
        },
    )
}

@Composable
private fun ExistingEnvironmentPicker(
    uiState: NewProjectUiState,
    onEnvironmentSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        uiState.availableEnvironments.forEach { environment ->
            ChoiceCard(
                selected = uiState.selectedEnvironmentId == environment.id,
                title = environment.label,
                body = environment.backend.name,
                onClick = { onEnvironmentSelected(environment.id) },
                trailing = {
                    EnvironmentBadge(
                        label = environment.state.name,
                        state = environment.state,
                        sharedWithCount = 0,
                    )
                },
            )
        }
    }
}

@Composable
private fun NewEnvironmentForm(
    uiState: NewProjectUiState,
    onNewEnvironmentLabelChanged: (String) -> Unit,
    onBackendSelected: (SandboxBackend) -> Unit,
    onImageSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.newEnvironmentLabel,
            onValueChange = onNewEnvironmentLabelChanged,
            label = { Text(stringResource(R.string.new_project_environment_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.new_project_image_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.availableImages.forEach { image ->
                ChoiceCard(
                    selected = uiState.selectedImageId == image.id,
                    title = image.label,
                    body = image.description,
                    onClick = { onImageSelected(image.id) },
                )
            }
        }

        // Only rendered when more than one backend is actually available, so
        // unrooted devices never see a chroot option they cannot use.
        if (uiState.availableBackends.size > 1) {
            Text(
                text = stringResource(R.string.new_project_backend_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.availableBackends.forEach { backend ->
                    FilterChip(
                        selected = uiState.selectedBackend == backend,
                        onClick = { onBackendSelected(backend) },
                        label = { Text(backend.name) },
                    )
                }
            }
            AnimatedVisibility(
                visible = uiState.selectedBackend == SandboxBackend.CHROOT,
                enter = fadeIn(motionSpec()),
                exit = fadeOut(motionSpec()),
            ) {
                Text(
                    text = stringResource(R.string.new_project_backend_chroot_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val CONTENT_PADDING_DP = 16
private const val MAX_FORM_WIDTH_DP = 560
private const val PROGRESS_STROKE_DP = 2
