package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.ThemeSettingsSchema
import dev.easyide.app.extensions.IconThemeChoice
import dev.easyide.app.ui.theme.LocalIconThemeChoices

/**
 * `workbench.iconTheme` on the user layer: the built-in icons or one of the icon themes enabled
 * extensions contribute (customization.md sec 9). A selected id no enabled extension provides stays
 * listed, marked as not installed, so the choice is not silently lost.
 */
@Composable
fun IconThemePickerRow(snapshot: SettingsSnapshot, actions: SettingActions) {
    val setting = ThemeSettingsSchema.iconTheme
    val current = snapshot[setting]
    val choices = LocalIconThemeChoices.current
    val builtIn = IconThemeChoice("", stringResource(R.string.icon_theme_builtin))
    val missing = current.takeIf { it.isNotEmpty() && choices.none { c -> c.id == it } }
    val options = listOf(builtIn) + choices + listOfNotNull(missing?.let { IconThemeChoice(it, stringResource(R.string.icon_theme_missing, it)) })
    PickerRow(
        id = setting.key,
        title = stringResource(R.string.setting_icon_theme_title),
        subtitle = stringResource(if (choices.isEmpty()) R.string.icon_theme_none_installed else R.string.setting_icon_theme_desc),
        labels = options.map { it.label },
        selected = options.indexOfFirst { it.id == current }.coerceAtLeast(0),
        modified = snapshot.isSetIn(setting, LayerId.USER),
        onReset = { actions.reset(setting, null) },
        onPick = { i -> options[i].id.let { if (it.isEmpty()) actions.reset(setting, null) else actions.set(setting, it, null) } },
    )
}
