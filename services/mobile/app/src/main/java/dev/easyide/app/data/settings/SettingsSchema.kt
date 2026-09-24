package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.theme.ThemeMode

/**
 * Every built-in setting, declared once. Storage, validation, the Settings
 * screen rows and search all derive from this list, so adding a setting is an
 * entry here plus its consumer - never a new flow/setter/key triple. Only keys
 * something actually reads are declared: a row that changes nothing is a bug.
 *
 * Key names follow docs/ux-overhaul/arch.md Pillar 5 and
 * docs/extension-sdk/sdk-reference.md "Settings keys". Contributed settings
 * join these at runtime through [SettingsRegistry].
 */
object SettingsSchema {

    /** Migrated from the pre-schema key `theme_mode` by [LegacySettingsMigration]. */
    val themeMode = Setting.Enum(
        key = "appearance.themeMode",
        category = SettingCategory.APPEARANCE,
        title = R.string.setting_theme_mode_title,
        description = R.string.setting_theme_mode_desc,
        default = ThemeMode.SYSTEM_DEFAULT,
        scope = SettingScope.G,
        values = ThemeMode.entries,
        label = ::themeModeLabel,
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

    /** Read by [SafeModeState]; the launcher shortcut and crash verdict add session-only reasons. */
    val safeMode = Setting.Bool(
        key = "extensions.safeMode",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_safe_mode_title,
        description = R.string.setting_safe_mode_desc,
        default = false,
        scope = SettingScope.G,
    )

    /** Read by [ProfileManager]; edited through the profiles list, never a free-text row. */
    val activeProfile = Setting.Str(
        key = "profiles.active",
        category = SettingCategory.APPEARANCE,
        title = R.string.setting_profile_title,
        description = R.string.setting_profile_desc,
        default = SettingsPolicy.DEFAULT_PROFILE,
        scope = SettingScope.G,
        pattern = SettingsPolicy.PROFILE_NAME,
    )

    val all: List<Setting<*>> = listOf(
        themeMode, editorFontSize, editorLineHeight, terminalFontSize, safeMode, activeProfile,
    ) + LspSettingsSchema.all

    /** Declared (validated, resolvable) but edited by a dedicated UI rather than a generic row. */
    val managedElsewhere: Set<String> = setOf(activeProfile.key)

    private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM_DEFAULT -> R.string.theme_mode_system_default
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
        ThemeMode.DYNAMIC -> R.string.theme_mode_dynamic
        ThemeMode.AMOLED_BLACK -> R.string.theme_mode_amoled_black
        ThemeMode.HIGH_CONTRAST -> R.string.theme_mode_high_contrast
    }
}
