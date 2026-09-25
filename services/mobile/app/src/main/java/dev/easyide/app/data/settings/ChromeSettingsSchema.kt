package dev.easyide.app.data.settings

import dev.easyide.app.R

/** When an on-screen editing aid shows: by the window and keyboard (`auto`), or whatever the user pins. */
enum class ChromeVisibility { AUTO, ALWAYS, NEVER }

/**
 * The settings of the chrome around the editor text: the touch toolbar and key row above the
 * keyboard (`workbench.*`, read by the input dock) and the breadcrumb row under the tabs. A file
 * of its own so [SettingsSchema] only lists it.
 */
object ChromeSettingsSchema {

    private fun label(mode: ChromeVisibility): Int = when (mode) {
        ChromeVisibility.AUTO -> R.string.setting_chrome_visibility_auto
        ChromeVisibility.ALWAYS -> R.string.setting_chrome_visibility_always
        ChromeVisibility.NEVER -> R.string.setting_chrome_visibility_never
    }

    private fun visibility(key: String, title: Int, description: Int) = Setting.Enum(
        key, SettingCategory.EDITOR, title, description, default = ChromeVisibility.AUTO, scope = SettingScope.G,
        values = ChromeVisibility.entries, label = ::label, id = { it.name.lowercase() },
    )

    /** The contributed touch toolbar, behind the dock's `more` button. */
    val touchToolbar = visibility("workbench.touchToolbar", R.string.setting_touch_toolbar_title, R.string.setting_touch_toolbar_desc)

    /** The editor and terminal key row. */
    val keyRow = visibility("workbench.keyRow", R.string.setting_key_row_title, R.string.setting_key_row_desc)

    val breadcrumbs = Setting.Bool(
        "breadcrumbs.enabled", SettingCategory.EDITOR, R.string.setting_breadcrumbs_title, R.string.setting_breadcrumbs_desc,
        default = true, scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(touchToolbar, keyRow, breadcrumbs)
}
