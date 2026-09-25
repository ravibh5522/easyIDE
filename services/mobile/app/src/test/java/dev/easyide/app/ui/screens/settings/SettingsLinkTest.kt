package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.ContributedSettings
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLinkTest {
    private fun contributed(owner: String, order: Int?, vararg keys: String) = ContributedSettings.parse(
        owner,
        obj(
            """{"title": "$owner", ${order?.let { "\"order\": $it," } ?: ""} "properties": {${keys.joinToString { "\"$it\": {\"type\": \"boolean\", \"default\": false}" }}}}""",
        ),
    ).settings

    private val settings = SettingsSchema.all + contributed("acme.b", 2, "acme.b.two", "acme.b.one") + contributed("acme.a", 1, "acme.a.only")

    @Test fun `the fragment names the first row of that extension's section`() {
        assertEquals("acme.a.only", SettingsLink.rowFor("acme.a", settings))
        assertEquals("acme.b.one", SettingsLink.rowFor("acme.b", settings))
    }

    @Test fun `an extension with no settings, an empty or a missing fragment marks nothing`() {
        assertNull(SettingsLink.rowFor("acme.none", settings))
        assertNull(SettingsLink.rowFor("", settings))
        assertNull(SettingsLink.rowFor(null, settings))
    }
}
