package dev.easyide.app.lsp

import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.lsp.TraceLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLspBridgeTest {

    private val provider = SettingsConfigurationProvider({ error("not used") }) { SettingsSchema.all }
    private val defaults = SettingsSnapshot.DEFAULTS

    @Test
    fun sectionIsTheTreeUnderItsPrefix() {
        val hover = provider.sectionOf(defaults, "editor.hover") as JsonObject
        assertEquals(JsonPrimitive(true), hover["enabled"])
        assertEquals(JsonPrimitive(500), hover["delay"])
        val editor = provider.sectionOf(defaults, "editor") as JsonObject
        assertTrue(editor["hover"] is JsonObject)
        assertEquals(JsonPrimitive(13), editor["fontSize"])
    }

    @Test
    fun exactKeyUnknownSectionAndWholeTree() {
        assertEquals(JsonPrimitive(500), provider.sectionOf(defaults, "editor.hover.delay"))
        assertEquals(JsonNull, provider.sectionOf(defaults, "python"))
        val all = provider.sectionOf(defaults, null).jsonObject
        assertTrue(all.containsKey("lsp") && all.containsKey("editor"))
    }

    @Test
    fun lspSettingsCarryTheSchemaDefaults() {
        val s = defaults.lspSettings()
        assertEquals(5000L, s.requestTimeoutMs)
        assertEquals(150L, s.didChangeDebounceMs)
        assertEquals(3, s.restartMaxRetries)
        assertEquals(2000L, s.restartBackoffMs)
        assertEquals(1200, s.globalMemoryBudgetMb)
        assertEquals(3, s.maxServers)
        assertEquals(TraceLevel.OFF, s.trace)
    }
}
