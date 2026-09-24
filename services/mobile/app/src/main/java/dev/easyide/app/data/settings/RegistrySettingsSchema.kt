package dev.easyide.app.data.settings

import dev.easyide.app.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** `extensions.autoCheckUpdates`: how often opening the Extensions screen refreshes registries. Notify only, never install. */
enum class AutoCheckUpdates { OFF, DAILY, WEEKLY }

/**
 * Registry keys of sdk-reference "Settings keys" (registry-and-install.md sec 3). There is no
 * public first-party index yet, so `extensions.registries` defaults to none and the
 * Extensions screen says so; a registry is added in settings.json as `{id, url, rootKey}`.
 */
object RegistrySettingsSchema {

    /** Entry shapes are checked by `RegistryConfigs.parse`, which reports each bad entry by reason. */
    val registries = Setting.Json(
        key = "extensions.registries",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_registries_title,
        description = R.string.setting_registries_desc,
        default = JsonArray(emptyList()),
        scope = SettingScope.G,
        accepts = { it is JsonArray && it.all { e -> e is JsonObject } },
    )

    val autoCheckUpdates = Setting.Enum(
        key = "extensions.autoCheckUpdates",
        category = SettingCategory.EXTENSIONS,
        title = R.string.setting_auto_check_updates_title,
        description = R.string.setting_auto_check_updates_desc,
        default = AutoCheckUpdates.WEEKLY,
        scope = SettingScope.G,
        values = AutoCheckUpdates.entries,
        label = ::autoCheckLabel,
    )

    val all: List<Setting<*>> = listOf(registries, autoCheckUpdates)

    private fun autoCheckLabel(v: AutoCheckUpdates): Int = when (v) {
        AutoCheckUpdates.OFF -> R.string.auto_check_off
        AutoCheckUpdates.DAILY -> R.string.auto_check_daily
        AutoCheckUpdates.WEEKLY -> R.string.auto_check_weekly
    }
}
