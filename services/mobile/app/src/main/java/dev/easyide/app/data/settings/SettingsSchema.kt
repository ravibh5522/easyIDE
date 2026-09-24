package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.extensions.settings.ExtensionSettings

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

    /**
     * A contributed colour theme's label (or `<extensionId>/<themeId>`); empty, or a
     * theme that is not installed and enabled, means the [themeMode] palette
     * (customization.md sec 8.1). Chosen in the theme picker, so no generic row.
     */
    val colorTheme = Setting.Str(
        key = "workbench.colorTheme",
        category = SettingCategory.APPEARANCE,
        title = R.string.setting_color_theme_title,
        description = R.string.setting_color_theme_desc,
        default = "",
        scope = SettingScope.G,
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

    // Extension keys: names and defaults come from :extensions (the runtime reads them
    // through its SettingsPort), so the two sides cannot disagree.

    val extensionsEnabled = Setting.Bool(
        key = ExtensionSettings.ENABLED.key,
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_extensions_enabled_title,
        description = R.string.setting_extensions_enabled_desc,
        default = ExtensionSettings.ENABLED.default,
        scope = SettingScope.G,
    )

    /** Written by the Extensions screen's enable toggle; the whole list per layer (arrays replace). */
    val extensionsDisabled = Setting.StrList(
        key = ExtensionSettings.DISABLED,
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_extensions_disabled_title,
        description = R.string.setting_extensions_disabled_desc,
        default = emptyList(),
        scope = SettingScope.P,
    )

    val contributionsHidden = Setting.StrList(
        key = ExtensionSettings.WORKBENCH_HIDDEN,
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_contributions_hidden_title,
        description = R.string.setting_contributions_hidden_desc,
        default = emptyList(),
        scope = SettingScope.P,
    )

    /** A key row id, or [KEY_ROWS_AUTO] for the first contributed row whose `when` holds. */
    val keyRowsActive = Setting.Str(
        key = "keyRows.active",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_key_rows_active_title,
        description = R.string.setting_key_rows_active_desc,
        default = KEY_ROWS_AUTO,
        scope = SettingScope.L,
    )

    const val KEY_ROWS_AUTO = "auto"

    val all: List<Setting<*>> = listOf(
        themeMode, colorTheme, editorFontSize, editorLineHeight, terminalFontSize, safeMode, activeProfile,
        extensionsEnabled, extensionsDisabled, contributionsHidden, keyRowsActive,
    ) + WorkbenchSettingsSchema.all + LspSettingsSchema.all + ThemeSettingsSchema.all + RegistrySettingsSchema.all + AuthoringSettingsSchema.all

    /** Declared (validated, resolvable) but edited by a dedicated UI rather than a generic row. */
    val managedElsewhere: Set<String> = setOf(activeProfile.key, colorTheme.key)

    private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM_DEFAULT -> R.string.theme_mode_system_default
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
        ThemeMode.DYNAMIC -> R.string.theme_mode_dynamic
        ThemeMode.AMOLED_BLACK -> R.string.theme_mode_amoled_black
        ThemeMode.HIGH_CONTRAST -> R.string.theme_mode_high_contrast
    }
}
