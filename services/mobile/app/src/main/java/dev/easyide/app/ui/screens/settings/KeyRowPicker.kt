package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.extensions.adapters.KeyRowChoice
import dev.easyide.extensions.contrib.Owner

/**
 * `keyRows.active` as a picker (customization.md sec 10): Automatic, then every row it can
 * name - the user's layouts, contributed rows and the built-in terminal row - in the order
 * `auto` tries them. Skipped `keyRows.layouts` rows are listed under it.
 */
@Composable
fun KeyRowPickerRow(
    state: KeyRowPickerState,
    snapshot: SettingsSnapshot,
    layer: LayerId,
    language: String?,
    actions: SettingActions,
    modifier: Modifier = Modifier,
) {
    val setting = SettingsSchema.keyRowsActive
    val current = snapshot.get(setting, language)
    val modifiedHere = snapshot.isSetIn(setting, layer, language)
    var open by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.key_row_auto)
    val currentLabel = when {
        current == SettingsSchema.KEY_ROWS_AUTO -> auto
        else -> state.choices.firstOrNull { it.id == current }?.title ?: stringResource(R.string.key_row_missing, current)
    }
    ListItem(
        headlineContent = { Text(setting.title.resolve()) },
        supportingContent = {
            Column {
                Text(setting.description.resolve())
                state.problems.forEach { p ->
                    Text(
                        stringResource(R.string.key_row_layout_problem, p.index + 1, p.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (modifiedHere) {
                    IconButton(onClick = { actions.reset(setting, language) }) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.setting_reset))
                    }
                }
                Box {
                    TextButton(onClick = { open = true }) { Text(currentLabel) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Column { Text(auto); Text(stringResource(R.string.key_row_auto_detail), style = MaterialTheme.typography.bodySmall) } },
                            onClick = { open = false; actions.set(setting, SettingsSchema.KEY_ROWS_AUTO, language) },
                        )
                        state.choices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Column { Text(choice.title); Text(choiceDetail(choice), style = MaterialTheme.typography.bodySmall) } },
                                onClick = { open = false; actions.set(setting, choice.id, language) },
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun choiceDetail(c: KeyRowChoice): String {
    val source = when (val o = c.owner) {
        null -> stringResource(R.string.key_row_user)
        Owner.BuiltIn -> stringResource(R.string.key_row_builtin)
        is Owner.Ext -> o.id.value
    }
    val parts = listOfNotNull(c.id, source, if (c.hidden) stringResource(R.string.key_row_hidden) else null)
    return parts.joinToString(" · ")
}
