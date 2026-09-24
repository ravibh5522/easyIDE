package dev.easyide.app.ui.screens.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import dev.easyide.app.data.settings.ContributedControl
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.Text as SettingText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Writes from a settings row into the screen's selected layer. Generic
 * methods, so one object serves every setting type; [language] targets a
 * `[lang]` block (the `@lang:` filter).
 */
interface SettingActions {
    fun <T> set(setting: Setting<T>, value: T, language: String?)
    fun setJson(setting: Setting<*>, value: JsonElement, language: String?)
    fun reset(setting: Setting<*>, language: String?)
}

/** Resolves a setting's display text outside composition (search needs it for every row). */
fun SettingText.resolve(resources: Resources): String = when (this) {
    is SettingText.Res -> resources.getString(id)
    is SettingText.Literal -> text
}

@Composable
fun SettingText.resolve(): String = when (this) {
    is SettingText.Res -> stringResource(id)
    is SettingText.Literal -> text
}

/**
 * One schema-driven row: the effective value for the selected layer (and
 * language), a control chosen by the setting's type, a modified dot and reset
 * while this layer holds a value, and "overridden by" when a higher layer wins
 * (CUS-02). Rows whose scope excludes the layer are shown read-only.
 */
@Composable
fun SettingRow(
    setting: Setting<*>,
    snapshot: SettingsSnapshot,
    layer: LayerId,
    language: String?,
    actions: SettingActions,
    onEditJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = setting.scope.allows(layer) && (language == null || setting.scope.languageOverridable)
    val resolved = snapshot.inspect(setting, language)
    val modifiedHere = snapshot.isSetIn(setting, layer, language)
    val winner = resolved.winner
    ListItem(
        headlineContent = { Text(setting.title.resolve()) },
        overlineContent = if (modifiedHere) {
            { Icon(Icons.Filled.Circle, contentDescription = stringResource(R.string.setting_modified_here), modifier = Modifier.widthIn(max = MODIFIED_DOT_DP.dp)) }
        } else {
            null
        },
        supportingContent = {
            Column {
                Text(setting.description.resolve())
                if (winner.layer.ordinal > layer.ordinal) {
                    Text(
                        stringResource(R.string.setting_overridden_by, stringResource(layerLabel(winner.layer)), winner.source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (snapshot.isInvalidIn(setting, layer, language)) {
                    Text(
                        stringResource(R.string.setting_invalid_here),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!editable) {
                    Text(stringResource(R.string.setting_not_in_layer), style = MaterialTheme.typography.bodySmall)
                }
                setting.deprecation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                WideControl(setting, resolved.value, editable, language, actions)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (modifiedHere) {
                    IconButton(onClick = { actions.reset(setting, language) }) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.setting_reset))
                    }
                }
                CompactControl(setting, resolved.value, editable, language, actions, onEditJson)
            }
        },
        modifier = modifier,
    )
}

/** Controls that fit beside the title: switches, steppers, pickers, the JSON link. */
@Composable
private fun CompactControl(
    setting: Setting<*>, value: Any?, enabled: Boolean, language: String?, actions: SettingActions, onEditJson: () -> Unit,
) {
    when (setting) {
        is Setting.Bool -> Switch(value as Boolean, onCheckedChange = { actions.set(setting, it, language) }, enabled = enabled)
        is Setting.IntRange -> Stepper(value as Int, setting.min, setting.max, setting.step, enabled) { actions.set(setting, it, language) }
        is Setting.Enum<*> -> EnumPicker(setting, value, enabled, language, actions)
        is Setting.Contributed -> {
            val json = value as JsonElement
            when (val c = setting.control) {
                ContributedControl.Switch -> Switch(
                    checked = (json as? JsonPrimitive)?.booleanOrNull == true,
                    onCheckedChange = { actions.setJson(setting, JsonPrimitive(it), language) },
                    enabled = enabled,
                )
                is ContributedControl.Stepper -> Stepper((json as? JsonPrimitive)?.intOrNull ?: c.min, c.min, c.max, 1, enabled) {
                    actions.setJson(setting, JsonPrimitive(it), language)
                }
                is ContributedControl.Choice -> ChoicePicker(
                    current = (json as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    options = c.values,
                    labelOf = { it },
                    detailOf = { c.descriptions.getOrNull(c.values.indexOf(it))?.takeIf(String::isNotEmpty) },
                    enabled = enabled,
                ) { actions.setJson(setting, JsonPrimitive(it), language) }
                ContributedControl.JsonOnly -> TextButton(onClick = onEditJson) { Text(stringResource(R.string.setting_edit_in_json)) }
                else -> Unit
            }
        }
        // Objects (lsp.servers, codeActionsOnSave) are edited in settings.json, like JsonOnly.
        is Setting.Json -> TextButton(onClick = onEditJson, enabled = enabled) { Text(stringResource(R.string.setting_edit_in_json)) }
        is Setting.Str, is Setting.StrList -> Unit
    }
}

/** Controls that need the row's full width: text fields and list editors. */
@Composable
private fun WideControl(setting: Setting<*>, value: Any?, enabled: Boolean, language: String?, actions: SettingActions) {
    when (setting) {
        is Setting.Str -> SettingTextField(value as String, singleLine = true, enabled = enabled) { actions.set(setting, it, language) }
        is Setting.StrList -> {
            @Suppress("UNCHECKED_CAST") // Setting.StrList decodes to List<String> by construction
            val lines = value as List<String>
            SettingTextField(lines.joinToString("\n"), singleLine = false, enabled = enabled) {
                actions.set(setting, it.lines().filter(String::isNotBlank), language)
            }
        }
        is Setting.Contributed -> ContributedWideControl(setting, value as JsonElement, enabled, language, actions)
        else -> Unit
    }
}

@Composable
private fun ContributedWideControl(setting: Setting.Contributed, value: JsonElement, enabled: Boolean, language: String?, actions: SettingActions) {
    when (setting.control) {
        is ContributedControl.TextField -> SettingTextField(
            initial = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            singleLine = true,
            enabled = enabled,
            isValid = { setting.isValid(JsonPrimitive(it)) },
        ) { actions.setJson(setting, JsonPrimitive(it), language) }
        ContributedControl.NumberField -> SettingTextField(
            initial = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content.orEmpty(),
            singleLine = true,
            enabled = enabled,
            isValid = { text -> text.toDoubleOrNull()?.let { setting.isValid(numberJson(text)) } == true },
        ) { text -> if (text.toDoubleOrNull() != null) actions.setJson(setting, numberJson(text), language) }
        ContributedControl.StringList -> SettingTextField(
            initial = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n").orEmpty(),
            singleLine = false,
            enabled = enabled,
        ) { text -> actions.setJson(setting, JsonArray(text.lines().filter(String::isNotBlank).map(::JsonPrimitive)), language) }
        else -> Unit
    }
}

/** An integer written as an integer, so an `integer`-typed schema accepts it. */
private fun numberJson(text: String): JsonPrimitive = text.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text.toDouble())

@Composable
private fun Stepper(value: Int, min: Int, max: Int, step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = enabled && value > min) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.setting_decrease))
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(min = STEPPER_VALUE_MIN_WIDTH_DP.dp),
        )
        IconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = enabled && value < max) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.setting_increase))
        }
    }
}

@Composable
private fun <E : Enum<E>> EnumPicker(setting: Setting.Enum<E>, value: Any?, enabled: Boolean, language: String?, actions: SettingActions) {
    @Suppress("UNCHECKED_CAST") // resolved values of a Setting.Enum<E> are E
    val current = value as E
    val labels = setting.values.associateWith { stringResource(setting.label(it)) }
    ChoicePicker(current, setting.values, { labels.getValue(it) }, enabled) { picked -> actions.set(setting, picked, language) }
}

@Composable
private fun <T> ChoicePicker(
    current: T?,
    options: List<T>,
    labelOf: (T) -> String,
    enabled: Boolean,
    detailOf: (T) -> String? = { null },
    onPick: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(current?.let(labelOf) ?: stringResource(R.string.setting_no_value))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(labelOf(option))
                            detailOf(option)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    onClick = { expanded = false; onPick(option) },
                )
            }
        }
    }
}

private const val STEPPER_VALUE_MIN_WIDTH_DP = 28
private const val MODIFIED_DOT_DP = 8
