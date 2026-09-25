package dev.easyide.app.ui.screens.settings

import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.ShellScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutModelTest {

    private val default = listOf("a", "b", "c", "d")

    @Test fun `no stored order shows the default order`() {
        assertEquals(default, NavOrdering.effective(default, emptyList()))
    }

    @Test fun `the stored order comes first and the rest follow in default order`() {
        assertEquals(listOf("c", "a", "b", "d"), NavOrdering.effective(default, listOf("c", "a")))
    }

    @Test fun `stored ids that no longer exist or repeat are dropped`() {
        assertEquals(listOf("b", "a", "c", "d"), NavOrdering.effective(default, listOf("gone", "b", "b", "a")))
    }

    @Test fun `moving swaps with the neighbour and holds at the ends`() {
        assertEquals(listOf("a", "c", "b", "d"), NavOrdering.moved(default, "c", -1))
        assertEquals(listOf("a", "b", "d", "c"), NavOrdering.moved(default, "c", 1))
        assertEquals(default, NavOrdering.moved(default, "a", -1))
        assertEquals(default, NavOrdering.moved(default, "d", 1))
        assertEquals(default, NavOrdering.moved(default, "missing", 1))
    }

    @Test fun `toggling adds and removes`() {
        assertEquals(listOf("x", "y"), NavOrdering.toggled(listOf("x"), "y"))
        assertEquals(listOf("x"), NavOrdering.toggled(listOf("x", "y"), "y"))
        assertEquals(listOf("x"), NavOrdering.toggled(emptyList(), "x"))
    }

    @Test fun `a placement override is written, replaced and removed by choosing the own placement`() {
        val start = JsonObject(emptyMap())
        val moved = PlacementEdit.with(start, "explorer", Placement.PANEL, own = Placement.SIDEBAR)
        assertEquals(JsonPrimitive("panel"), moved["explorer"])
        val replaced = PlacementEdit.with(moved, "explorer", Placement.SECONDARY_SIDEBAR, own = Placement.SIDEBAR)
        assertEquals(mapOf("explorer" to JsonPrimitive("secondarySidebar")), replaced)
        assertTrue(PlacementEdit.with(replaced, "explorer", Placement.SIDEBAR, own = Placement.SIDEBAR).isEmpty())
    }

    @Test fun `placement edits leave other containers alone`() {
        val start = JsonObject(mapOf("scm" to JsonPrimitive("panel")))
        val next = PlacementEdit.with(start, "explorer", Placement.PANEL, own = Placement.SIDEBAR)
        assertEquals(setOf("scm", "explorer"), next.keys)
    }

    @Test fun `the core catalog lists both scopes' navigation and every container once`() {
        val catalog = LayoutCatalog.core()
        assertEquals(listOf(CoreShell.HOME, CoreShell.EXTENSIONS, CoreShell.SETTINGS), catalog.navigation.filter { it.scope == ShellScope.APP }.map { it.id })
        assertTrue(catalog.navigation.any { it.scope == ShellScope.WORKSPACE && it.id == CoreShell.FILES })
        assertEquals(catalog.containers.map { it.id }.distinct(), catalog.containers.map { it.id })
        assertTrue(catalog.containers.any { it.id == CoreShell.EXPLORER && Placement.SIDEBAR in it.allowed })
        assertTrue(catalog.navigation.all { it.pack == null } && catalog.containers.all { it.pack == null })
    }

    @Test fun `a catalog built from registries names the pack of every contributed entry`() {
        val docker = ContainerSpec("acme.docker.panel", "Docker", IconRef("x"), Placement.SIDEBAR, ScopeFilter.APP)
        val nav = NavItem("acme.docker.nav", "Docker", IconRef("x"), NavTarget.Container(docker.id), 100, ScopeFilter.APP)
        val containers = CoreShell.containers().register(docker, Origin.Extension("acme.docker")).registry
        val navigation = CoreShell.navigation().register(nav, Origin.Extension("acme.docker")).registry

        val catalog = LayoutCatalog.of(navigation, containers)

        assertEquals("acme.docker", catalog.containers.single { it.id == docker.id }.pack)
        assertEquals("acme.docker", catalog.navigation.single { it.id == nav.id }.pack)
        assertEquals(null, catalog.containers.single { it.id == CoreShell.EXPLORER }.pack)
        assertEquals(LayoutCatalog.core().containers, catalog.containers - catalog.containers.filter { it.pack != null }.toSet())
    }
}
