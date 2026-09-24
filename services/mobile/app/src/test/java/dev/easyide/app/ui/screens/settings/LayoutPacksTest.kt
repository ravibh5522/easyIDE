package dev.easyide.app.ui.screens.settings

import dev.easyide.app.ui.props.ShellSettingsSchema
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extension screens in Settings, Layout: the catalog names the packs, and each pack can be switched off as a whole. */
class LayoutPacksTest {
    private fun catalog(vararg packs: String): LayoutCatalog {
        var containers = CoreShell.containers()
        var navigation = CoreShell.navigation()
        packs.forEach { p ->
            val c = ContainerSpec("$p.main", "Box", IconRef("x"), Placement.SIDEBAR, ScopeFilter.BOTH)
            containers = containers.register(c, Origin.Extension(p)).registry
            navigation = navigation.register(NavItem("$p.nav", "Box", IconRef("x"), NavTarget.Container(c.id), 300, ScopeFilter.BOTH), Origin.Extension(p)).registry
        }
        return LayoutCatalog.of(navigation, containers)
    }

    @Test fun `the packs are the extensions that added anything, once each and sorted`() {
        assertEquals(listOf("acme.a", "acme.b"), catalog("acme.b", "acme.a").packs)
        assertTrue(LayoutCatalog.core().packs.isEmpty())
    }

    @Test fun `off keeps the key, on removes it, and other packs are left alone`() {
        val start = Json.parseToJsonElement("""{ "acme.a": false }""")
        val both = ShellSettingsSchema.withExtension(start, "acme.b", on = false)
        assertEquals(JsonObject(mapOf("acme.a" to JsonPrimitive(false), "acme.b" to JsonPrimitive(false))), both)
        assertEquals(JsonObject(mapOf("acme.b" to JsonPrimitive(false))), ShellSettingsSchema.withExtension(both, "acme.a", on = true))
        assertEquals(JsonObject(emptyMap()), ShellSettingsSchema.withExtension(JsonObject(emptyMap()), "acme.a", on = true))
        assertEquals(JsonObject(mapOf("acme.a" to JsonPrimitive(false))), ShellSettingsSchema.withExtension(JsonPrimitive("junk"), "acme.a", on = false))
    }
}
