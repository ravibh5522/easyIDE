package dev.easyide.app.ui.props

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingScope
import dev.easyide.app.data.settings.SettingsRegistry
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.layer
import dev.easyide.app.data.settings.obj
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.nav.NavLabels
import dev.easyide.app.ui.shell.nav.NavPosition
import dev.easyide.app.ui.shell.nav.NavSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSettingsSchemaTest {

    private val schema = SchemaState.builtInOnly(SettingsSchema.all)
    private fun snapshot(user: String) = SettingsSnapshot(schema, listOf(layer(LayerId.USER, user)))

    @Test fun `the shell keys are registered once in the built-in schema, all under shell`() {
        assertTrue(ShellSettingsSchema.all.all { it.key.startsWith("shell.") })
        assertTrue(SettingsSchema.all.containsAll(ShellSettingsSchema.all))
        val keys = SettingsSchema.all.map { it.key }
        assertEquals("duplicate keys: " + keys.groupBy { it }.filter { it.value.size > 1 }.keys, keys.distinct(), keys)
        SettingsRegistry(SettingsSchema.all)
    }

    @Test fun `stored navigation values reach the surface`() {
        val settings = ShellSettingsSchema.navSettings(
            snapshot(
                """{"shell.navigation.order":["settings","home"],"shell.navigation.hidden":["extensions"],
                    "shell.navigation.pinned":["home"],"shell.navigation.position":"right","shell.navigation.labels":"never"}""",
            ),
        )
        assertEquals(NavPrefs(listOf("settings", "home"), setOf("extensions"), setOf("home")), settings.prefs)
        assertEquals(NavPosition.RIGHT, settings.position)
        assertEquals(NavLabels.NEVER, settings.labels)
    }

    @Test fun `an invalid navigation value falls back to the default`() {
        val settings = ShellSettingsSchema.navSettings(snapshot("""{"shell.navigation.position":"diagonal","shell.navigation.order":"home"}"""))
        assertEquals(NavPosition.AUTO, settings.position)
        assertTrue(settings.prefs.order.isEmpty())
    }

    @Test fun `only the layout preset may be set by a project`() {
        val projectAllowed = ShellSettingsSchema.all.filter { it.scope.allows(LayerId.PROJECT) }
        assertEquals(listOf(ShellSettingsSchema.layoutPreset), projectAllowed)
        assertEquals(SettingScope.P, ShellSettingsSchema.layoutPreset.scope)
    }

    @Test fun `navigation choices store their string ids and reject enum names`() {
        val position = ShellSettingsSchema.navigationPosition
        assertEquals(listOf("auto", "left", "right", "bottom"), position.ids)
        NavPosition.entries.forEach { assertEquals(it, position.decode(position.encode(it))) }
        assertNull(position.decode(JsonPrimitive("BOTTOM")))
        assertEquals(listOf("auto", "always", "never"), ShellSettingsSchema.navigationLabels.ids)
    }

    @Test fun `a preset id is a name, not a path or empty`() {
        val preset = ShellSettingsSchema.layoutPreset
        listOf("auto", "focus", "terminalFirst", "acme.split-view").forEach { assertTrue(it, preset.isValid(JsonPrimitive(it))) }
        listOf("", "9lives", "a b", "../x", "x".repeat(65)).forEach { assertFalse(it, preset.isValid(JsonPrimitive(it))) }
    }

    @Test fun `placement values must name a known placement`() {
        val setting = ShellSettingsSchema.containerPlacement
        assertTrue(setting.isValid(obj("""{"explorer": "secondarySidebar", "acme.docker": "panel"}""")))
        assertFalse(setting.isValid(obj("""{"explorer": "floating"}""")))
        assertFalse(setting.isValid(obj("""{"explorer": 3}""")))
        assertFalse(setting.isValid(JsonArray(emptyList())))
        assertFalse(setting.isValid(JsonNull))
    }

    @Test fun `prefs read the stored ids and ignore placements the shell does not know`() {
        val s = snapshot(
            """{"shell.navigation.order": ["git", "files"], "shell.navigation.hidden": ["search"], "shell.navigation.pinned": ["files"],
                "shell.containers.placement": {"explorer": "secondarySidebar"}, "shell.containers.hidden": ["outline"]}""",
        )
        val nav = ShellSettingsSchema.navPrefs(s)
        assertEquals(listOf("git", "files"), nav.order)
        assertEquals(setOf("search"), nav.hidden)
        assertEquals(setOf("files"), nav.pinned)
        val containers = ShellSettingsSchema.containerPrefs(s)
        assertEquals(mapOf("explorer" to Placement.SECONDARY_SIDEBAR), containers.placement)
        assertEquals(setOf("outline"), containers.hidden)
        val mixed = obj("""{"a": "panel", "b": "nowhere", "c": 1}""")
        assertEquals(mapOf("a" to Placement.PANEL), ShellSettingsSchema.placementMap(mixed))
        assertEquals(emptyMap<String, Placement>(), ShellSettingsSchema.placementMap(JsonObject(emptyMap())))
    }

    @Test fun `defaults leave the shell exactly as it is`() {
        val s = SettingsSnapshot(schema, emptyList())
        assertEquals("auto", s[ShellSettingsSchema.layoutPreset])
        assertEquals(NavPosition.AUTO, s[ShellSettingsSchema.navigationPosition])
        assertEquals(NavLabels.AUTO, s[ShellSettingsSchema.navigationLabels])
        assertEquals(NavSettings(), ShellSettingsSchema.navSettings(SettingsSnapshot.DEFAULTS))
        assertEquals(LayoutPresets.AUTO, ShellSettingsSchema.layoutPreset.default)
        assertTrue(ShellSettingsSchema.navPrefs(s).order.isEmpty())
        assertTrue(ShellSettingsSchema.containerPrefs(s).placement.isEmpty())
    }
}
