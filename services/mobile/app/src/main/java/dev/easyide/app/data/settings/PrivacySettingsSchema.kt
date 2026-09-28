package dev.easyide.app.data.settings

import dev.easyide.app.R

/**
 * The single telemetry consent (decision 0031): analytics and push announcements together. The
 * first-launch gate sets it; the key stays here so withdrawing is as easy as giving. It is
 * device-wide, and nothing is collected or registered while it is false.
 */
object PrivacySettingsSchema {

    val telemetryEnabled = Setting.Bool(
        key = "privacy.telemetry.enabled",
        category = SettingCategory.PRIVACY,
        title = R.string.setting_telemetry_title,
        description = R.string.setting_telemetry_desc,
        default = false,
        scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(telemetryEnabled)
}
