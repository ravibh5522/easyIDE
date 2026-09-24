package dev.easyide.app.extensions.registry

import dev.easyide.app.data.settings.RegistrySettingsSchema
import dev.easyide.app.extensions.registry.RegistryWorld.Companion.json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RegistryConfigsTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test fun `the default is no registry`() {
        assertEquals(JsonArray(emptyList()), RegistrySettingsSchema.registries.default)
        assertEquals(RegistryConfigs.NONE, RegistryConfigs.parse(RegistrySettingsSchema.registries.default))
    }

    @Test fun `valid entries parse, each bad one is reported by reason`() {
        val r = RegistryConfigs.parse(json(listOf(
            mapOf("id" to "main", "url" to "https://example.org/index", "rootKey" to key),
            mapOf("id" to "plain", "url" to "http://example.org/", "rootKey" to key),
            mapOf("id" to "short", "url" to "https://example.org/", "rootKey" to "AAAA"),
            mapOf("id" to "Bad/Id", "url" to "https://example.org/", "rootKey" to key),
            mapOf("id" to "main", "url" to "https://mirror.example.org/", "rootKey" to key),
        )))
        assertEquals(listOf("main"), r.registries.map { it.id })
        assertEquals("https://example.org/index/index.json", r.registries.single().fileUrl("index.json"))
        assertEquals(4, r.problems.size)
        assertTrue(r.problems[0].contains("https"))
        assertTrue(r.problems[1].contains("rootKey"))
        assertTrue(r.problems[3].contains("duplicate"))
    }
}
