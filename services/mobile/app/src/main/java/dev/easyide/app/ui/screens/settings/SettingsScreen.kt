package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingCategory
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.components.EnvironmentBadge
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * Settings: schema-driven rows (theme, editor, terminal) with search, plus
 * project storage and environment management. Environments are
 * listed here because deleting one is a cross-project action - the usage count
 * shown per row is what makes the "in use" refusal understandable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    errorMessage: String?,
    externalFolderSync: ExternalFolderSync,
    settingActions: SettingActions,
    onDefaultEnvironmentSelected: (String) -> Unit,
    onDeleteEnvironment: (String) -> Unit,
    onDefaultProjectsFolderChosen: (String?) -> Unit,
    onDefaultProjectsFolderPickFailed: () -> Unit,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    val resources = LocalContext.current.resources
    // Search covers title, description and key, as in VS Code's settings UI.
    val visible = SettingsSchema.all.filter { setting ->
        query.isBlank() || listOf(
            resources.getString(setting.title),
            resources.getString(setting.description),
            setting.key,
        ).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val pickFolder = rememberFolderPicker(
        externalFolderSync = externalFolderSync,
        onPicked = { uri -> onDefaultProjectsFolderChosen(uri.toString()) },
        onFailed = onDefaultProjectsFolderPickFailed,
    )

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onErrorShown()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = LIST_BOTTOM_PADDING_DP.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.contentWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SettingCategory.entries.forEach { category ->
                val rows = visible.filter { it.category == category }
                if (rows.isEmpty()) return@forEach
                item(key = category.name) { SectionHeader(stringResource(category.title)) }
                items(rows, key = { it.key }) { setting ->
                    SettingRow(
                        setting = setting,
                        snapshot = uiState.settings,
                        actions = settingActions,
                        modifier = Modifier.contentWidth(),
                    )
                }
                item { HorizontalDivider(modifier = Modifier.contentWidth().padding(vertical = 8.dp)) }
            }

            // Storage and environments are not schema settings; while searching
            // only matching schema rows are shown.
            if (query.isNotBlank()) return@LazyColumn

            item { SectionHeader(stringResource(R.string.settings_storage_section)) }

            item {
                StorageFolderRow(
                    folderName = uiState.defaultProjectsFolderName,
                    onChoose = pickFolder,
                    onClear = { onDefaultProjectsFolderChosen(null) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.contentWidth().padding(vertical = 8.dp)) }

            item { SectionHeader(stringResource(R.string.settings_sandbox_section)) }

            if (uiState.environments.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_environments_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.contentWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(uiState.environments, key = { it.environment.id }) { item ->
                EnvironmentRow(
                    item = item,
                    isDefault = uiState.defaultEnvironmentId == item.environment.id,
                    onSetDefault = { onDefaultEnvironmentSelected(item.environment.id) },
                    onDelete = { onDeleteEnvironment(item.environment.id) },
                )
            }
        }
    }
}

@Composable
private fun EnvironmentRow(
    item: EnvironmentListItem,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(item.environment.label) },
        supportingContent = {
            Text(
                text = buildString {
                    append(
                        if (item.usedByProjectCount == 0) {
                            stringResource(R.string.settings_environment_unused)
                        } else {
                            stringResource(R.string.settings_environment_used_by, item.usedByProjectCount)
                        }
                    )
                    if (isDefault) append(" - ${stringResource(R.string.settings_environment_is_default)}")
                },
            )
        },
        leadingContent = {
            EnvironmentBadge(
                label = item.environment.backend.name,
                state = item.environment.state,
                sharedWithCount = 0,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onSetDefault) {
                    Icon(
                        imageVector = if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.settings_set_default_environment),
                        tint = if (isDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.settings_delete_environment),
                    )
                }
            }
        },
        modifier = Modifier.contentWidth(),
    )
}

/**
 * The default location suggested when creating a new project. A folder here
 * does not move existing projects - it only pre-fills the choice on the next
 * "New project" screen, which can always be overridden per project.
 */
@Composable
private fun StorageFolderRow(
    folderName: String?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(folderName ?: stringResource(R.string.settings_storage_app_default))
        },
        supportingContent = {
            Text(
                if (folderName != null) {
                    stringResource(R.string.settings_storage_folder_chosen_body)
                } else {
                    stringResource(R.string.settings_storage_app_default_body)
                }
            )
        },
        leadingContent = {
            Icon(imageVector = Icons.Filled.Folder, contentDescription = null)
        },
        trailingContent = {
            Row {
                if (folderName != null) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.settings_storage_clear)) }
                }
                TextButton(onClick = onChoose) { Text(stringResource(R.string.settings_storage_choose_folder)) }
            }
        },
        modifier = Modifier.contentWidth(),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Column(
        modifier = Modifier
            .contentWidth()
            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Keeps settings rows readable instead of stretching across a wide tablet. */
private fun Modifier.contentWidth(): Modifier =
    this.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH_DP.dp)

private const val MAX_CONTENT_WIDTH_DP = 720
private const val LIST_BOTTOM_PADDING_DP = 32
