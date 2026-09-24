package dev.easyide.app.data.settings

import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.nav.NavLabels
import dev.easyide.app.ui.shell.nav.NavPosition
import dev.easyide.app.ui.shell.nav.NavSettings
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSettingsSchemaTest {
    private fun snapshot(user: String) =
        SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all + ShellSettingsSchema.all), listOf(layer(LayerId.USER, user)))

    @Test fun `every key is a shell key and unique among all settings`() {
        assertTrue(ShellSettingsSchema.all.all { it.key.startsWith("shell.") })
        val keys = (SettingsSchema.all + ShellSettingsSchema.all).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `defaults are the documented ones`() {
        assertEquals(NavSettings(), ShellSettingsSchema.navSettings(SettingsSnapshot.DEFAULTS))
        assertEquals(LayoutPresets.AUTO, ShellSettingsSchema.layoutPreset.default)
    }

    @Test fun `stored values reach the surface`() {
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

    @Test fun `an invalid value falls back to the default`() {
        val settings = ShellSettingsSchema.navSettings(snapshot("""{"shell.navigation.position":"diagonal","shell.navigation.order":"home"}"""))
        assertEquals(NavPosition.AUTO, settings.position)
        assertTrue(settings.prefs.order.isEmpty())
    }

    @Test fun `a preset id is a plain token`() {
        assertNotNull(ShellSettingsSchema.layoutPreset.decode(JsonPrimitive("acme.dock-2")))
        assertNull(ShellSettingsSchema.layoutPreset.decode(JsonPrimitive("has space")))
        assertNull(ShellSettingsSchema.layoutPreset.decode(JsonPrimitive("")))
    }
}
