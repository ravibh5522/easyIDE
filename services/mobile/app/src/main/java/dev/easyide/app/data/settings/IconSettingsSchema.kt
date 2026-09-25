package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.extensions.adapters.IconOverrides
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The user's own file and folder icon associations, laid in front of the active icon theme's
 * mappings (docs/extension-sdk/icon-themes.md). Both are objects of name -> icon id of the active
 * theme, edited in settings.json; they merge key-wise across profile layers. A separate file so
 * [SettingsSchema] only lists it.
 */
object IconSettingsSchema {

    private val stringMap: (JsonElement) -> Boolean = { v -> v is JsonObject && v.values.all { it is JsonPrimitive && it.isString } }

    val fileAssociations = Setting.Json(
        IconOverrides.FILE_KEY, SettingCategory.APPEARANCE, R.string.setting_icon_file_assoc_title,
        R.string.setting_icon_file_assoc_desc, default = JsonObject(emptyMap()), scope = SettingScope.G,
        accepts = stringMap, merge = Merge.OBJECT,
    )

    val folderAssociations = Setting.Json(
        IconOverrides.FOLDER_KEY, SettingCategory.APPEARANCE, R.string.setting_icon_folder_assoc_title,
        R.string.setting_icon_folder_assoc_desc, default = JsonObject(emptyMap()), scope = SettingScope.G,
        accepts = stringMap, merge = Merge.OBJECT,
    )

    val all: List<Setting<*>> = listOf(fileAssociations, folderAssociations)
}
