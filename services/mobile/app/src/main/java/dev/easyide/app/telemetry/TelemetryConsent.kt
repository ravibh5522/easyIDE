package dev.easyide.app.telemetry

import dev.easyide.app.data.settings.PrivacySettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.diagnostics.BuildInfo
import dev.easyide.telemetry.Telemetry
import dev.easyide.telemetry.TelemetryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Applies the single consent setting to the provider. This is the only place that turns collection
 * on, so every other call site can log unconditionally: with consent off the provider drops it.
 */
class TelemetryConsent(
    private val telemetry: Telemetry,
    private val settings: Flow<SettingsSnapshot>,
    private val build: BuildInfo,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            settings.map { it[PrivacySettingsSchema.telemetryEnabled] }.distinctUntilChanged().collect { on ->
                applyAnalytics(on)
                applyPush(on)
                applyDiagnostics(on)
            }
        }
    }

    private fun applyAnalytics(on: Boolean) {
        telemetry.analytics.setEnabled(on)
        if (!on) return
        deviceProperties(build).forEach { (k, v) -> telemetry.analytics.setUserProperty(k, v) }
        telemetry.analytics.log(
            TelemetryNames.EVENT_APP_OPEN,
            mapOf(TelemetryNames.PARAM_VERSION to build.versionName, TelemetryNames.PARAM_BUILD to build.versionCode.toString()),
        )
    }

    private fun applyDiagnostics(on: Boolean) {
        telemetry.crash.setEnabled(on)
        telemetry.performance.setEnabled(on)
        if (!on) return
        deviceProperties(build).forEach { (k, v) -> telemetry.crash.setKey(k, v) }
        telemetry.remoteConfig.refresh()
    }

    private fun applyPush(on: Boolean) {
        telemetry.push.setEnabled(on)
        if (!on) return
        telemetry.push.subscribe(TelemetryNames.TOPIC_ALL)
        telemetry.push.subscribe(TelemetryNames.TOPIC_CHANNEL_PREFIX + topicChannel(build))
    }

    companion object {
        /** Firebase user-property values are capped at 36 characters. */
        private const val VALUE_MAX = 36

        /** `canary.42+abc1234` -> `canary`; topics only allow `[a-zA-Z0-9-_.~%]`. */
        fun topicChannel(build: BuildInfo): String = build.channel.substringBefore('.').substringBefore('+')

        /** Everything the device-info dashboards need, with no identifier beyond what the provider assigns. */
        fun deviceProperties(build: BuildInfo): Map<String, String> = mapOf(
            TelemetryNames.PROP_CHANNEL to topicChannel(build),
            TelemetryNames.PROP_MAKER to build.manufacturer,
            TelemetryNames.PROP_MODEL to build.model,
            TelemetryNames.PROP_SDK to build.sdkInt.toString(),
            TelemetryNames.PROP_ABI to build.abis.firstOrNull().orEmpty(),
        ).mapValues { it.value.take(VALUE_MAX) }.filterValues { it.isNotEmpty() }
    }
}
