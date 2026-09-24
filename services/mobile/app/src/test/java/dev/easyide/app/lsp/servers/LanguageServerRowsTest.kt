package dev.easyide.app.lsp.servers

import dev.easyide.app.data.settings.Layer
import dev.easyide.app.data.settings.LayerDoc
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageServerRowsTest {

    private val pyright = ServerDeclaration("easyide.python/pyright", setOf("python"), listOf("pyright-langserver", "--stdio"), extensionId = "easyide.python")
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun snapshot(user: String, project: String = "{}") = SettingsSnapshot(
        SchemaState.builtInOnly(SettingsSchema.all),
        listOf(Layer(LayerId.USER, LayerDoc.fromJson(obj(user)), "user"), Layer(LayerId.PROJECT, LayerDoc.fromJson(obj(project)), "project")),
    )

    @Test fun `pack and custom servers with their state, and rejected entries as issues`() {
        val s = snapshot(
            """{"lsp.servers": {"zls": {"languages": ["zig"], "command": ["zls"]}, "half": {"command": ["x"], "priority": "high"}}}""",
            """{"lsp.servers": {"easyide.python/pyright": {"enabled": false}}}""",
        )
        val userLayer = s.layer(LayerId.USER)!!.doc.plain["lsp.servers"] as JsonObject
        val view = LanguageServerRows.build(listOf(pyright), s, userLayer)
        assertEquals(listOf("easyide.python/pyright", "zls"), view.rows.map { it.key })
        val py = view.rows[0]
        assertFalse(py.enabled)
        assertTrue(py.customized)
        assertFalse(py.setInLayer)
        assertFalse(py.isCustom)
        val zls = view.rows[1]
        assertTrue(zls.isCustom && zls.enabled && zls.setInLayer)
        assertEquals(listOf("zls"), zls.command)
        assertEquals(listOf(ServerIssue("half", setOf(RejectReason.NO_LANGUAGES), listOf("priority"))), view.issues)
    }

    @Test fun `edits touch one entry of the edited layer`() {
        val layer = obj("""{"a": {"languages": ["x"], "command": ["a"], "env": {"K": "v"}}, "b": {"enabled": false}}""")
        assertEquals(setOf("b"), LanguageServerRows.withEntry(layer, "a", null)!!.keys)
        assertNull(LanguageServerRows.withEntry(obj("""{"a": {}}"""), "a", null))
        assertNull(LanguageServerRows.withEnabled(layer.filterKeys { it == "b" }.let(::JsonObject), "b", enabled = true, offBelow = false))
        assertEquals(obj("""{"enabled": true}"""), LanguageServerRows.withEnabled(null, "b", enabled = true, offBelow = true)!!["b"])
        assertEquals(obj("""{"enabled": false}"""), LanguageServerRows.withEnabled(null, "p/q", enabled = false, offBelow = false)!!["p/q"])
        val edited = LanguageServerRows.customEntry("zig, zon zig", " zls \n--flag with space\n\n", layer["a"] as JsonObject)!!
        assertEquals(obj("""{"languages": ["zig", "zon"], "command": ["zls", "--flag with space"], "env": {"K": "v"}}"""), edited)
        assertNull(LanguageServerRows.customEntry("", "zls", null))
        assertNull(LanguageServerRows.customEntry("zig", "  ", null))
        assertEquals("zig, zon" to "zls\n--flag with space", LanguageServerRows.formText(edited))
        assertTrue(LanguageServerRows.isValidCustomKey("my-zig"))
        assertFalse(LanguageServerRows.isValidCustomKey("acme/zig"))
        assertFalse(LanguageServerRows.isValidCustomKey(" x"))
    }
}
