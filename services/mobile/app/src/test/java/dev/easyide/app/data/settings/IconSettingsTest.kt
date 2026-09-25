package dev.easyide.app.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `easyide.icons.*Associations`: registered, edited as JSON under Appearance, checked against the active theme's icon ids. */
class IconSettingsTest {

    private val schema = SettingsRegistry(SettingsSchema.all).state.value

    private fun diagnostics(text: String, ids: Set<String>?) = SettingsJsonDiagnostics.check(text, LayerId.USER, schema, ids)

    @Test fun `both settings are registered under Appearance with the object shape`() {
        for (s in IconSettingsSchema.all) {
            assertTrue(s.key, schema.byKey[s.key] === s)
            assertEquals(SettingCategory.APPEARANCE, (s.group as SettingGroup.BuiltIn).category)
            assertEquals(Merge.OBJECT, s.merge)
            assertTrue(s.isValid(Json.parseToJsonElement("""{"*.foo": "python"}""")))
            assertFalse(s.isValid(Json.parseToJsonElement("""{"*.foo": 3}""")))
            assertFalse(s.isValid(Json.parseToJsonElement("""["python"]""")))
        }
    }

    @Test fun `an unknown icon id is reported against the active theme, known ids are not`() {
        val text = """{"easyide.icons.fileAssociations": {"*.foo": "python", "x": "typo"}, "easyide.icons.folderAssociations": {"api": "folder-api", "b": "nope"}}"""
        val d = diagnostics(text, setOf("python", "folder-api")).filter { it.code == DiagnosticCode.UNKNOWN_ICON }
        assertEquals(listOf("easyide.icons.fileAssociations" to "typo", "easyide.icons.folderAssociations" to "nope"), d.map { it.key to it.detail })
        assertEquals(emptyList<Any>(), diagnostics(text, setOf("python", "typo", "folder-api", "nope")).filter { it.code == DiagnosticCode.UNKNOWN_ICON })
    }

    @Test fun `with the built-in icons active there is nothing to check against`() {
        val text = """{"easyide.icons.fileAssociations": {"*.foo": "anything"}}"""
        assertEquals(emptyList<Any>(), diagnostics(text, null).filter { it.code == DiagnosticCode.UNKNOWN_ICON })
    }

    @Test fun `a wrong-shaped value is an invalid value, not a crash`() {
        val d = diagnostics("""{"easyide.icons.fileAssociations": ["a"]}""", setOf("a"))
        assertEquals(listOf(DiagnosticCode.INVALID_VALUE), d.map { it.code })
    }
}
