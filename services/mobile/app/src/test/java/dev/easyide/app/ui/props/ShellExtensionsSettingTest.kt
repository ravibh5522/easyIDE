package dev.easyide.app.ui.props

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `shell.extensions.contribute` (extension-ui.md section 6): per-extension on/off for UI contributions only. */
class ShellExtensionsSettingTest {
    private val schema = SchemaState.builtInOnly(SettingsSchema.all)
    private fun snapshot(user: String) = SettingsSnapshot(schema, listOf(layer(LayerId.USER, user)))

    @Test fun `every pack is on unless the user switched it off`() {
        assertTrue(ShellSettingsSchema.extensionsOff(snapshot("{}")).isEmpty())
        assertEquals(setOf("acme.a"), ShellSettingsSchema.extensionsOff(snapshot("""{"shell.extensions.contribute":{"acme.a":false,"acme.b":true}}""")))
    }

    @Test fun `a malformed value is refused and leaves everything on`() {
        assertTrue(ShellSettingsSchema.extensionsOff(snapshot("""{"shell.extensions.contribute":{"acme.a":"no"}}""")).isEmpty())
        assertTrue(ShellSettingsSchema.extensionsOff(snapshot("""{"shell.extensions.contribute":["acme.a"]}""")).isEmpty())
    }

    @Test fun `a project layer cannot set it`() {
        assertTrue(!ShellSettingsSchema.extensionsContribute.scope.allows(LayerId.PROJECT))
    }
}
