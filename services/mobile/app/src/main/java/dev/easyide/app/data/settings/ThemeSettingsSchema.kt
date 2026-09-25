package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.theme.ColorCustomizations
import kotlinx.serialization.json.JsonObject

/**
 * Colour customization keys of customization.md 8.3 (all G scope, so profile-scoped). Each is
 * a JSON object edited in settings.json; `"[<theme label>]"` blocks apply to one theme. The
 * objects merge key-wise across layers, so a `[theme]` block in one layer does not erase the
 * top-level entries of another.
 */
object ThemeSettingsSchema {

    val colorCustomizations = Setting.Json(
        "workbench.colorCustomizations", SettingCategory.APPEARANCE, R.string.setting_color_customizations_title,
        R.string.setting_color_customizations_desc, default = JsonObject(emptyMap()), scope = SettingScope.G,
        accepts = { it is JsonObject }, merge = Merge.OBJECT,
    )

    val tokenColorCustomizations = Setting.Json(
        "editor.tokenColorCustomizations", SettingCategory.APPEARANCE, R.string.setting_token_color_customizations_title,
        R.string.setting_token_color_customizations_desc, default = JsonObject(emptyMap()), scope = SettingScope.G,
        accepts = { it is JsonObject }, merge = Merge.OBJECT,
    )

    val semanticTokenColorCustomizations = Setting.Json(
        "editor.semanticTokenColorCustomizations", SettingCategory.APPEARANCE, R.string.setting_semantic_token_customizations_title,
        R.string.setting_semantic_token_customizations_desc, default = JsonObject(emptyMap()), scope = SettingScope.G,
        accepts = { it is JsonObject }, merge = Merge.OBJECT,
    )

    /** The Material Icon Theme of the built-in `easyide.material-icons` pack, selected until the user picks another. */
    const val DEFAULT_ICON_THEME = "material-icon-theme"

    /**
     * A contributed icon theme id; empty (an explicit choice of the built-in icons), or an id that
     * no enabled extension provides, means the built-in icons.
     */
    val iconTheme = Setting.Str(
        "workbench.iconTheme", SettingCategory.APPEARANCE, R.string.setting_icon_theme_title,
        R.string.setting_icon_theme_desc, default = DEFAULT_ICON_THEME, scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(iconTheme, colorCustomizations, tokenColorCustomizations, semanticTokenColorCustomizations)

    /** The three values as the theme layer consumes them. */
    fun customizations(s: SettingsSnapshot): ColorCustomizations = ColorCustomizations(
        colors = s[colorCustomizations] as? JsonObject ?: JsonObject(emptyMap()),
        tokenColors = s[tokenColorCustomizations] as? JsonObject ?: JsonObject(emptyMap()),
        semanticTokenColors = s[semanticTokenColorCustomizations] as? JsonObject ?: JsonObject(emptyMap()),
    )
}
