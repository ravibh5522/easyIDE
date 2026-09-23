package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot

/** Writes from a settings row. Generic methods, so one object serves every setting type. */
interface SettingActions {
    fun <T> set(setting: Setting<T>, value: T)
    fun reset(setting: Setting<*>)
}

/**
 * One schema-driven row: title, description and a control chosen by the
 * setting's type, plus a reset button while the user layer holds a value.
 */
@Composable
fun SettingRow(
    setting: Setting<*>,
    snapshot: SettingsSnapshot,
    actions: SettingActions,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(setting.title)) },
        supportingContent = {
            Column {
                Text(stringResource(setting.description))
                when (setting) {
                    is Setting.Str -> TextSetting(snapshot[setting], singleLine = true) { actions.set(setting, it) }
                    is Setting.StrList -> TextSetting(snapshot[setting].joinToString("\n"), singleLine = false) {
                        actions.set(setting, it.lines().filter(String::isNotBlank))
                    }
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot.isModified(setting)) {
                    IconButton(onClick = { actions.reset(setting) }) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.setting_reset))
                    }
                }
                when (setting) {
                    is Setting.Bool -> Switch(snapshot[setting], onCheckedChange = { actions.set(setting, it) })
                    is Setting.IntRange -> IntStepper(setting, snapshot[setting], actions)
                    is Setting.Enum<*> -> EnumPicker(setting, snapshot, actions)
                    is Setting.Str, is Setting.StrList -> Unit
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun IntStepper(setting: Setting.IntRange, value: Int, actions: SettingActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { actions.set(setting, (value - setting.step).coerceAtLeast(setting.min)) },
            enabled = value > setting.min,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.setting_decrease))
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(min = STEPPER_VALUE_MIN_WIDTH_DP.dp),
        )
        IconButton(
            onClick = { actions.set(setting, (value + setting.step).coerceAtMost(setting.max)) },
            enabled = value < setting.max,
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.setting_increase))
        }
    }
}

@Composable
private fun <E : Enum<E>> EnumPicker(setting: Setting.Enum<E>, snapshot: SettingsSnapshot, actions: SettingActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(setting.label(snapshot[setting]))) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            setting.values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(stringResource(setting.label(value))) },
                    onClick = { expanded = false; actions.set(setting, value) },
                )
            }
        }
    }
}

/**
 * The field keeps its own text while editing: each keystroke is written to the
 * store, but echoing the stored value back would arrive a frame late and move
 * the cursor.
 */
@Composable
private fun TextSetting(initial: String, singleLine: Boolean, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val STEPPER_VALUE_MIN_WIDTH_DP = 28
