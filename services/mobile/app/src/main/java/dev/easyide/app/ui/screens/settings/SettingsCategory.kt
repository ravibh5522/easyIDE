package dev.easyide.app.ui.screens.settings

import androidx.annotation.StringRes
import dev.easyide.app.R
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingCategory
import dev.easyide.app.data.settings.SettingGroup

/**
 * The pages of Settings (docs/ui-redesign/screens.md 5), in the order the panel lists them. [id] is
 * the last segment of the document URI `easyide://settings/<id>`, so it is a stable spelling and
 * never an enum name. Schema settings are assigned by [of]; pages without schema rows (Keyboard,
 * Sandbox, Diagnostics, Advanced) hold controls of their own.
 */
enum class SettingsCategory(val id: String, @StringRes val title: Int) {
    APPEARANCE("appearance", R.string.settings_theme_section),
    EDITOR("editor", R.string.settings_category_editor),
    TERMINAL("terminal", R.string.settings_category_terminal),
    FILES("files", R.string.settings_category_files),
    GIT("git", R.string.settings_category_git),
    SANDBOX("sandbox", R.string.settings_category_sandbox),
    KEYBOARD("keyboard", R.string.settings_keyboard_shortcuts),
    LANGUAGE_SERVERS("language-servers", R.string.settings_category_language_servers),
    EXTENSIONS("extensions", R.string.settings_category_extensions),
    LAYOUT("layout", R.string.settings_category_layout),
    DIAGNOSTICS("diagnostics", R.string.diag_settings_entry_title),
    ADVANCED("advanced", R.string.settings_category_advanced);

    /** Whether the page holds schema rows a layer can override; the others are device-wide or link elsewhere. */
    val layered: Boolean get() = this !in DEVICE_WIDE

    companion object {
        private val DEVICE_WIDE = setOf(KEYBOARD, SANDBOX, DIAGNOSTICS, ADVANCED)

        /** The results page across categories; not listed in the panel. */
        const val SEARCH_ID = "search"

        fun ofId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }

        /** Explorer keys are declared under the editor category but read as file handling. */
        private const val EXPLORER_PREFIX = "explorer."

        /** The page that lists [setting]; every contributed setting goes to Extensions. */
        fun of(setting: Setting<*>): SettingsCategory = when (val group = setting.group) {
            is SettingGroup.Contributed -> EXTENSIONS
            is SettingGroup.BuiltIn -> when (group.category) {
                SettingCategory.APPEARANCE -> APPEARANCE
                SettingCategory.EDITOR -> if (setting.key.startsWith(EXPLORER_PREFIX)) FILES else EDITOR
                SettingCategory.TERMINAL -> TERMINAL
                SettingCategory.GIT -> GIT
                SettingCategory.LANGUAGE_SERVERS -> LANGUAGE_SERVERS
                SettingCategory.EXTENSIONS -> EXTENSIONS
                SettingCategory.WORKSPACE -> FILES
                SettingCategory.LAYOUT -> LAYOUT
            }
        }
    }
}
