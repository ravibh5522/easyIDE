package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.theme.ThemeMode

/**
 * Every built-in setting, declared once. Storage, validation, the Settings
 * screen rows and search all derive from this list, so adding a setting is an
 * entry here plus its consumer - never a new flow/setter/key triple.
 *
 * Key names follow docs/ux-overhaul/arch.md Pillar 5 and
 * docs/extension-sdk/sdk-reference.md "Settings keys".
 */
object SettingsSchema {

    /** Stored under the pre-schema key `theme_mode`, so existing choices survive. */
    val themeMode = Setting.Enum(
        key = "appearance.themeMode",
        category = SettingCategory.APPEARANCE,
        title = R.string.setting_theme_mode_title,
        description = R.string.setting_theme_mode_desc,
        default = ThemeMode.SYSTEM_DEFAULT,
        scope = SettingScope.G,
        values = ThemeMode.entries,
        label = ::themeModeLabel,
        storeKey = "theme_mode",
    )

    val editorFontSize = Setting.IntRange(
        key = "editor.fontSize",
        category = SettingCategory.EDITOR,
        title = R.string.setting_editor_font_size_title,
        description = R.string.setting_editor_font_size_desc,
        default = 13,
        scope = SettingScope.L,
        min = 8,
        max = 32,
    )

    val editorLineHeight = Setting.IntRange(
        key = "editor.lineHeight",
        category = SettingCategory.EDITOR,
        title = R.string.setting_editor_line_height_title,
        description = R.string.setting_editor_line_height_desc,
        default = 20,
        scope = SettingScope.L,
        min = 10,
        max = 48,
    )

    val terminalFontSize = Setting.IntRange(
        key = "terminal.fontSize",
        category = SettingCategory.TERMINAL,
        title = R.string.setting_terminal_font_size_title,
        description = R.string.setting_terminal_font_size_desc,
        default = 13,
        scope = SettingScope.G,
        min = 8,
        max = 32,
    )

    val all: List<Setting<*>> = listOf(themeMode, editorFontSize, editorLineHeight, terminalFontSize)

    private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM_DEFAULT -> R.string.theme_mode_system_default
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
        ThemeMode.DYNAMIC -> R.string.theme_mode_dynamic
        ThemeMode.AMOLED_BLACK -> R.string.theme_mode_amoled_black
        ThemeMode.HIGH_CONTRAST -> R.string.theme_mode_high_contrast
    }
}
