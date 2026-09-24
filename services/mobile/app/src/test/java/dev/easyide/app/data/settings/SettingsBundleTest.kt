package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsBundleTest {

    private val schema = SchemaState.builtInOnly(SettingsSchema.all)

    private fun export(): ByteArray {
        val out = ByteArrayOutputStream()
        SettingsBundle.write(
            out,
            ExportContent(
                appVersion = "1.0",
                exportedAt = "2026-09-24T00:00:00Z",
                userSettings = LayerDoc.fromJson(obj("{\"editor.fontSize\": 15, \"extensions.safeMode\": true, \"profiles.active\": \"work\", \"[go]\": {\"editor.fontSize\": 12}}")),
                keybindings = "// mine\n[{\"key\": \"f5\", \"command\": \"workbench.action.terminal.new\"}]",
                profiles = mapOf("work" to ProfileContent(obj("{\"terminal.fontSize\": 11, \"extensions.limits.fileMb\": 1}"), JsonArray(emptyList()), null, null)),
                extensions = listOf(JsonObject(mapOf("id" to JsonPrimitive("acme.x")))),
            ),
        )
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private val manifest = "{\"format\": \"easyide-settings\", \"formatVersion\": 1}"

    @Test
    fun roundTripExcludesAppLevelKeys() {
        val bytes = export()
        val bundle = SettingsBundle.read(ByteArrayInputStream(bytes), schema)
        assertEquals(json("15"), bundle.settings!!.plain["editor.fontSize"])
        assertEquals(json("12"), bundle.settings!!.lang["go"]!!["editor.fontSize"])
        assertNull(bundle.settings!!.plain["extensions.safeMode"])
        assertNull(bundle.settings!!.plain["profiles.active"])
        assertTrue(bundle.keybindings!!.startsWith("// mine"))
        assertEquals(setOf("terminal.fontSize"), bundle.profiles.getValue("work").settings.keys)
        assertEquals("acme.x", (bundle.extensions.single()["id"] as JsonPrimitive).content)
        // No device-level state leaks into the archive bytes.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("safeMode"))
        assertFalse(text.contains("trust:"))
    }

    @Test
    fun hostileArchivesAreRefused() {
        fun refused(bytes: ByteArray) = runCatching { SettingsBundle.read(ByteArrayInputStream(bytes), schema) }.exceptionOrNull() is BundleException
        assertTrue(refused(zip("manifest.json" to manifest, "../evil.json" to "{}")))
        assertTrue(refused(zip("manifest.json" to manifest, "/abs.json" to "{}")))
        assertTrue(refused(zip("manifest.json" to manifest, "profiles\\x.json" to "{}")))
        assertTrue(refused(zip("settings.json" to "{}")))
        assertTrue(refused(zip("manifest.json" to "{\"format\": \"other\", \"formatVersion\": 1}")))
        assertTrue(refused(zip("manifest.json" to manifest, "settings.json" to "{ bad")))
        assertTrue(refused(zip("manifest.json" to manifest, "profiles/p.json" to "[]")))
        val huge = "{\"a\": \"" + "x".repeat(SettingsPolicy.IMPORT_MAX_BYTES.toInt()) + "\"}"
        assertTrue(refused(zip("manifest.json" to manifest, "settings.json" to huge)))
    }

    @Test
    fun unknownEntriesAreIgnoredAndInvalidValuesReported() {
        val bundle = SettingsBundle.read(
            ByteArrayInputStream(
                zip(
                    "manifest.json" to manifest,
                    "settings.json" to "{\"editor.fontSize\": 999, \"future.key\": 1}",
                    "snippets/python.json" to "{}",
                    "profiles/default.json" to "{}",
                ),
            ),
            schema,
        )
        assertEquals(listOf("snippets/python.json", "profiles/default.json"), bundle.ignored)
        assertEquals(DiagnosticCode.INVALID_VALUE, bundle.diagnostics.single().code)
        assertEquals(json("1"), bundle.settings!!.plain["future.key"])
    }

    @Test
    fun pathRules() {
        assertTrue(SettingsBundle.isSafePath("profiles/a.json"))
        assertFalse(SettingsBundle.isSafePath("a/../b"))
        assertFalse(SettingsBundle.isSafePath("c:x"))
        assertFalse(SettingsBundle.isSafePath("a//b"))
    }
}
