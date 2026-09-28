package dev.easyide.app.telemetry

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.layer
import dev.easyide.app.diagnostics.BuildInfo
import dev.easyide.telemetry.Analytics
import dev.easyide.telemetry.Push
import dev.easyide.telemetry.Telemetry
import dev.easyide.telemetry.TelemetryNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryConsentTest {

    private val build = BuildInfo("0.2.0-canary.42+abc1234", 43, "Xiaomi", "Pad 6", 34, listOf("arm64-v8a", "armeabi-v7a"))

    private class Recorder : Analytics, Push {
        val events = mutableListOf<String>()
        val props = mutableMapOf<String, String>()
        val topics = mutableSetOf<String>()
        override fun setEnabled(enabled: Boolean) = Unit
        override fun log(event: String, params: Map<String, String>) { events += event }
        override fun setUserProperty(name: String, value: String) { props[name] = value }
        override fun subscribe(topic: String) { topics += topic }
        override fun unsubscribe(topic: String) { topics -= topic }
    }

    private fun snapshot(user: String) = SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all), listOf(layer(LayerId.USER, user)))

    @Test
    fun deviceInfoIsCappedAndChannelIsTopicSafe() {
        val props = TelemetryConsent.deviceProperties(build)
        assertEquals("canary", props[TelemetryNames.PROP_CHANNEL])
        assertEquals("Pad 6", props[TelemetryNames.PROP_MODEL])
        assertEquals("arm64-v8a", props[TelemetryNames.PROP_ABI])
        assertEquals("34", props[TelemetryNames.PROP_SDK])
        assertTrue(TelemetryConsent.deviceProperties(build.copy(model = "x".repeat(80))).getValue(TelemetryNames.PROP_MODEL).length <= 36)
        assertEquals("release", TelemetryConsent.topicChannel(build.copy(versionName = "0.2.0")))
    }

    @Test
    fun nothingIsSentUntilTheUserOptsIn() = runTest {
        val rec = Recorder()
        val settings = MutableStateFlow(snapshot("{}"))
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        TelemetryConsent(Telemetry(rec, rec), settings, build, scope).start()
        scope.advanceUntilIdle()
        assertTrue(rec.events.isEmpty() && rec.props.isEmpty() && rec.topics.isEmpty())

        settings.value = snapshot("""{"privacy.telemetry.enabled": true}""")
        scope.advanceUntilIdle()
        assertEquals(listOf(TelemetryNames.EVENT_APP_OPEN), rec.events)
        assertFalse(rec.props.isEmpty())
        assertEquals(setOf("all", "channel_canary"), rec.topics)
        scope.cancel()
    }
}
