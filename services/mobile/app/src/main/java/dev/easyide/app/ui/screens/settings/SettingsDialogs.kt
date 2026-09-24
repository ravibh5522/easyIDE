package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.data.settings.ImportMode
import dev.easyide.app.data.settings.ImportPreview
import dev.easyide.app.data.settings.SettingsPolicy

/**
 * Profiles list (LLD 14): switch by tapping, create (empty or a copy of the
 * active one), rename and delete. `default` and the active profile cannot be
 * renamed or deleted, which [dev.easyide.app.data.settings.ProfileManager] enforces too.
 */
@Composable
fun ProfilesDialog(
    profiles: List<String>,
    active: String,
    onSwitch: (String) -> Unit,
    onCreate: (name: String, copyFrom: String?) -> Unit,
    onRename: (from: String, to: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var copyActive by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val all = listOf(SettingsPolicy.DEFAULT_PROFILE) + profiles
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_profiles_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT_DP.dp)) {
                    items(all, key = { it }) { name ->
                        val mutable = name != SettingsPolicy.DEFAULT_PROFILE && name != active
                        ListItem(
                            headlineContent = { Text(name) },
                            leadingContent = { RadioButton(selected = name == active, onClick = { onSwitch(name) }) },
                            trailingContent = {
                                if (mutable) {
                                    Row {
                                        IconButton(onClick = { renaming = name; newName = name }) {
                                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.settings_profile_rename))
                                        }
                                        IconButton(onClick = { onDelete(name) }) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.settings_profile_delete))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                val valid = SettingsPolicy.PROFILE_NAME.matches(newName.trim()) && newName.trim() !in all
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = newName.isNotEmpty() && !valid,
                    label = { Text(stringResource(if (renaming != null) R.string.settings_profile_new_name else R.string.settings_profile_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (renaming == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = copyActive, onCheckedChange = { copyActive = it })
                        Text(stringResource(R.string.settings_profile_copy_active, active))
                    }
                }
                TextButton(
                    enabled = valid,
                    onClick = {
                        val from = renaming
                        if (from != null) onRename(from, newName) else onCreate(newName, active.takeIf { copyActive })
                        renaming = null
                        newName = ""
                    },
                ) { Text(stringResource(if (renaming != null) R.string.settings_profile_rename else R.string.settings_profile_create)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

/** What an import would do, before anything is written (LLD 15 "preview"). */
@Composable
fun ImportPreviewDialog(preview: ImportPreview, onConfirm: (ImportMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(pluralStringResource(R.plurals.settings_import_settings, preview.settingCount, preview.settingCount))
                Text(pluralStringResource(R.plurals.settings_import_keybindings, preview.keybindingCount, preview.keybindingCount))
                if (preview.profileNames.isNotEmpty()) {
                    Text(stringResource(R.string.settings_import_profiles, preview.profileNames.joinToString()))
                }
                if (preview.clashes.isNotEmpty()) {
                    Text(stringResource(R.string.settings_import_clashes, preview.clashes.joinToString()), color = MaterialTheme.colorScheme.tertiary)
                }
                val invalid = preview.bundle.diagnostics.size
                if (invalid > 0) {
                    Text(pluralStringResource(R.plurals.settings_import_invalid, invalid, invalid), color = MaterialTheme.colorScheme.error)
                }
                if (preview.bundle.extensions.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.settings_import_extensions, preview.bundle.extensions.size, preview.bundle.extensions.size))
                }
                if (preview.bundle.ignored.isNotEmpty()) {
                    Text(stringResource(R.string.settings_import_ignored, preview.bundle.ignored.joinToString()))
                }
                Text(stringResource(R.string.settings_import_modes), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onConfirm(ImportMode.MERGE) }) { Text(stringResource(R.string.settings_import_merge)) }
                TextButton(onClick = { onConfirm(ImportMode.REPLACE) }) { Text(stringResource(R.string.settings_import_replace)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ResetAllDialog(layerName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_all_title)) },
        text = { Text(stringResource(R.string.settings_reset_all_body, layerName)) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.settings_reset_all_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val LIST_MAX_HEIGHT_DP = 240
