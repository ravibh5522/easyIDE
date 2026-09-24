package dev.easyide.app.data.settings

import dev.easyide.app.R

/**
 * Extension authoring keys (sdk-reference "Settings keys"). `extensions.developerMode` is
 * app-level ([SettingsPolicy.APP_LEVEL]): it gates developer installs pushed over adb
 * (`easyide-ext dev`) and requested from a project (`dev --local`), so a repository can never
 * switch it on.
 */
object AuthoringSettingsSchema {

    val developerMode = Setting.Bool(
        key = "extensions.developerMode",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_developer_mode_title,
        description = R.string.setting_developer_mode_desc,
        default = false,
        scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(developerMode)
}
