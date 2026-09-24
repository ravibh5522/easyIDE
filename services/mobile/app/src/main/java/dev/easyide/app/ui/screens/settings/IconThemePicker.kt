package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.ThemeSettingsSchema
import dev.easyide.app.extensions.IconThemeChoice
import dev.easyide.app.ui.theme.LocalIconThemeChoices
import dev.easyide.app.ui.theme.Spacing

/**
 * `workbench.iconTheme` on the user layer: the built-in icons or one of the icon themes
 * enabled extensions contribute (customization.md sec 9). A selected id no enabled extension
 * provides stays shown, marked as not installed, so the choice is not silently lost.
 */
@Composable
fun IconThemePickerRow(snapshot: SettingsSnapshot, actions: SettingActions, modifier: Modifier = Modifier) {
    val setting = ThemeSettingsSchema.iconTheme
    val current = snapshot[setting]
    val choices = LocalIconThemeChoices.current
    val builtIn = IconThemeChoice("", stringResource(R.string.icon_theme_builtin))
    val missing = current.takeIf { it.isNotEmpty() && choices.none { c -> c.id == it } }
    val options = listOf(builtIn) + choices + listOfNotNull(missing?.let { IconThemeChoice(it, stringResource(R.string.icon_theme_missing, it)) })
    var expanded by remember { mutableStateOf(false) }

    Row(modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_icon_theme_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (choices.isEmpty()) R.string.icon_theme_none_installed else R.string.setting_icon_theme_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.isSetIn(setting, LayerId.USER)) {
            IconButton(onClick = { actions.reset(setting, null) }) {
                Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.setting_reset))
            }
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.id == current }?.label ?: builtIn.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            if (option.id.isEmpty()) actions.reset(setting, null) else actions.set(setting, option.id, null)
                        },
                    )
                }
            }
        }
    }
}
