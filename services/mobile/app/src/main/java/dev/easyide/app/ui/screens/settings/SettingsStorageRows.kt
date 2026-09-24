package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.EnvironmentBadge
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.sandbox.external.ExternalFolderSync

@Composable
fun EnvironmentRow(
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
                        tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
fun StorageFolderRow(
    folderName: String?,
    externalFolderSync: ExternalFolderSync,
    onChosen: (String?) -> Unit,
    onPickFailed: () -> Unit,
) {
    val pickFolder = rememberFolderPicker(
        externalFolderSync = externalFolderSync,
        onPicked = { uri -> onChosen(uri.toString()) },
        onFailed = onPickFailed,
    )
    ListItem(
        headlineContent = { Text(folderName ?: stringResource(R.string.settings_storage_app_default)) },
        supportingContent = {
            Text(
                if (folderName != null) {
                    stringResource(R.string.settings_storage_folder_chosen_body)
                } else {
                    stringResource(R.string.settings_storage_app_default_body)
                }
            )
        },
        leadingContent = { Icon(imageVector = Icons.Filled.Folder, contentDescription = null) },
        trailingContent = {
            Row {
                if (folderName != null) {
                    TextButton(onClick = { onChosen(null) }) { Text(stringResource(R.string.settings_storage_clear)) }
                }
                TextButton(onClick = pickFolder) { Text(stringResource(R.string.settings_storage_choose_folder)) }
            }
        },
        modifier = Modifier.contentWidth(),
    )
}
