package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryTest {

    private val python = ConfigurationContribution(
        owner = "easyide.python",
        configuration = json(
            """
            {"title": "Python", "order": 2, "properties": {
              "python.linting.enabled": {"type": "boolean", "default": true, "scope": "resource"},
              "python.venvPath": {"type": "string", "default": "", "markdownDescription": "See [docs](https://x.y) for **details**", "scope": "machine"},
              "python.level": {"type": "string", "enum": ["a", "b"], "enumDescriptions": ["A", "B"], "default": "a", "scope": "language-overridable"},
              "python.jobs": {"type": "integer", "minimum": 1, "maximum": 8, "default": 99},
              "python.env": {"type": "object", "default": {}, "scope": "machine-overridable"},
              "python.paths": {"type": "array", "items": {"type": "string"}, "default": []},
              "editor.fontSize": {"type": "integer", "default": 99},
              "bad": 5
            }}
            """,
        ),
        configurationDefaults = obj("{\"editor.fontSize\": 15, \"[python]\": {\"editor.lineHeight\": 22}, \"profiles.active\": \"x\", \"editor.lineHeight\": 1000}"),
    )
    private val other = ConfigurationContribution(
        owner = "acme.other",
        configuration = json("[{\"properties\": {\"python.linting.enabled\": {\"type\": \"string\"}, \"acme.flag\": {\"type\": \"boolean\"}}}]"),
        configurationDefaults = obj("{\"editor.fontSize\": 16}"),
    )

    private fun registry() = SettingsRegistry(SettingsSchema.all).also { it.setContributions(listOf(python, other)) }

    @Test
    fun contributedDescriptorsBecomeSettings() {
        val state = registry().state.value
        val lint = state.byKey["python.linting.enabled"] as Setting.Contributed
        assertEquals("easyide.python", lint.owner)
        assertEquals(SettingScope.P, lint.scope)
        assertEquals(ContributedControl.Switch, lint.control)
        assertEquals("Linting: Enabled", ContributedSettings.titleOf(lint.key))
        assertEquals(SettingGroup.Contributed("easyide.python", "Python", 2), lint.group)

        val venv = state.byKey["python.venvPath"] as Setting.Contributed
        assertEquals(SettingScope.G, venv.scope)
        assertEquals(Text.Literal("See docs (https://x.y) for details"), venv.description)

        val level = state.byKey["python.level"] as Setting.Contributed
        assertEquals(SettingScope.L, level.scope)
        assertEquals(ContributedControl.Choice(listOf("a", "b"), listOf("A", "B")), level.control)
        assertFalse(level.isValid(JsonPrimitive("c")))

        assertEquals(SettingScope.E, state.byKey["python.env"]!!.scope)
        assertEquals(Merge.OBJECT, state.byKey["python.env"]!!.merge)
        assertEquals(ContributedControl.StringList, (state.byKey["python.paths"] as Setting.Contributed).control)
        assertEquals(ContributedControl.Stepper(1, 8), (state.byKey["python.jobs"] as Setting.Contributed).control)
    }

    @Test
    fun conflictsFollowTheRules() {
        val state = registry().state.value
        // Built-in key wins; the contribution is reported.
        assertTrue(state.byKey["editor.fontSize"] === SettingsSchema.editorFontSize)
        assertTrue(state.diagnostics.any { it.diagnostic.code == DiagnosticCode.CONTRIBUTED_BUILT_IN_KEY && it.diagnostic.key == "editor.fontSize" })
        // Earlier owner keeps a shared key.
        assertEquals("easyide.python", (state.byKey["python.linting.enabled"] as Setting.Contributed).owner)
        assertTrue(state.diagnostics.any { it.owner == "acme.other" && it.diagnostic.code == DiagnosticCode.CONTRIBUTED_DUPLICATE })
        // A default failing its own schema registers with no default.
        assertEquals(JsonNull, state.byKey["python.jobs"]!!.default)
        assertTrue(state.diagnostics.any { it.diagnostic.code == DiagnosticCode.CONTRIBUTED_BAD_DEFAULT })
        assertTrue(state.diagnostics.any { it.diagnostic.code == DiagnosticCode.CONTRIBUTED_BAD_DESCRIPTOR && it.diagnostic.key == "bad" })
    }

    @Test
    fun configurationDefaultsLaterExtensionWinsAndProtectedKeysAreDropped() {
        val state = registry().state.value
        val snapshot = SettingsSnapshot(state, state.extensionDefaults)
        assertEquals(16, snapshot[SettingsSchema.editorFontSize])
        assertEquals("extension acme.other", snapshot.inspect(SettingsSchema.editorFontSize).winner.source)
        assertEquals(22.0, snapshot.get(EditorSettingsSchema.lineHeight, "python"), 0.0)
        // Out of range for its target: dropped, so the built-in default stands.
        assertEquals(EditorSettingsSchema.lineHeight.default, snapshot[EditorSettingsSchema.lineHeight], 0.0)
        assertNull(state.extensionDefaults.first().doc.plain["profiles.active"])
        assertTrue(state.diagnostics.count { it.diagnostic.code == DiagnosticCode.DEFAULTS_REJECTED } >= 2)
    }

    @Test
    fun removingAnOwnerRemovesItsSchema() {
        val r = registry()
        r.setContributions(listOf(other))
        val state = r.state.value
        assertNull(state.byKey["python.venvPath"])
        assertEquals("acme.other", (state.byKey["python.linting.enabled"] as Setting.Contributed).owner)
    }

    @Test
    fun scopeMapping() {
        assertEquals(SettingScope.G, ContributedSettings.scopeOf("application"))
        assertEquals(SettingScope.G, ContributedSettings.scopeOf("machine"))
        assertEquals(SettingScope.E, ContributedSettings.scopeOf("environment"))
        assertEquals(SettingScope.P, ContributedSettings.scopeOf("window"))
        assertEquals(SettingScope.P, ContributedSettings.scopeOf("project"))
        assertEquals(SettingScope.P, ContributedSettings.scopeOf(null))
        assertEquals(SettingScope.L, ContributedSettings.scopeOf("language-overridable"))
    }

    @Test
    fun schemaValidatorSubset() {
        val schema = obj(
            """{"type": "object", "required": ["a"], "additionalProperties": false,
               "properties": {"a": {"type": "integer", "minimum": 0}, "b": {"type": "array", "items": {"type": "string", "pattern": "^x"}, "maxItems": 2},
               "c": {"anyOf": [{"type": "string"}, {"type": "boolean"}]}}}""",
        )
        assertTrue(SchemaValidator.isValid(schema, json("{\"a\": 1, \"b\": [\"xa\"], \"c\": true}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"b\": []}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": -1}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": 1.5}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": 1, \"b\": [\"y\"]}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": 1, \"b\": [\"x\", \"x\", \"x\"]}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": 1, \"c\": 3}")))
        assertFalse(SchemaValidator.isValid(schema, json("{\"a\": 1, \"z\": 3}")))
        assertTrue(SchemaValidator.isValid(obj("{\"type\": \"number\", \"enum\": [1, 2]}"), json("1.0")))
    }
}
