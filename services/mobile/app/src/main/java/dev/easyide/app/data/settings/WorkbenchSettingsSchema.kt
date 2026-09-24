package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.settings.ExtensionSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Customization keys for contributions and key rows (customization.md sec 6 and 10) that
 * have a dedicated editor (the Extensions inspector, the key row picker) and are also
 * reachable as JSON. `workbench.stages.placement` is not declared: contributed stages are
 * not rendered yet, so the key would change nothing (see the M4 tracker row).
 */
object WorkbenchSettingsSchema {

    /** `{location: [id...]}`; objects merge by location across layers (customization.md sec 2). */
    val contributionsOrder = Setting.Json(
        key = ExtensionSettings.WORKBENCH_ORDER,
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_contributions_order_title,
        description = R.string.setting_contributions_order_desc,
        default = JsonObject(emptyMap()),
        scope = SettingScope.P,
        accepts = ContributionOverrides::isOrderValue,
        merge = Merge.OBJECT,
    )

    /** User key rows; the shape of each entry is checked by `UserKeyRows`, which reports bad rows. */
    val keyRowLayouts = Setting.Json(
        key = "keyRows.layouts",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_key_rows_layouts_title,
        description = R.string.setting_key_rows_layouts_desc,
        default = JsonArray(emptyList()),
        scope = SettingScope.G,
        accepts = { it is JsonArray && it.all { row -> row is JsonObject } },
    )

    val all: List<Setting<*>> = listOf(contributionsOrder, keyRowLayouts)
}
