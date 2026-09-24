package dev.easyide.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerRegistryTest {
    private val docker = Origin.Extension("acme.docker")

    private fun spec(id: String, placement: Placement = Placement.SIDEBAR, scope: ScopeFilter = ScopeFilter.BOTH, locations: Set<Placement> = Placement.entries.toSet()) =
        ContainerSpec(id, id, IconRef("i"), placement, scope, locations)

    private fun ContainerRegistry.ids(p: Placement, s: ShellScope, prefs: ContainerPrefs = ContainerPrefs()) =
        inPlacement(p, s, prefs).map { it.id }

    @Test
    fun `core containers fill each placement per scope`() {
        val r = CoreShell.containers()
        assertEquals(listOf("home.projects", "extensions.list", "settings.categories"), r.ids(Placement.SIDEBAR, ShellScope.APP))
        assertEquals(listOf("explorer", "search", "scm"), r.ids(Placement.SIDEBAR, ShellScope.WORKSPACE))
        assertEquals(listOf("outline"), r.ids(Placement.SECONDARY_SIDEBAR, ShellScope.WORKSPACE))
        assertEquals(listOf("terminal", "problems", "output"), r.ids(Placement.PANEL, ShellScope.WORKSPACE))
        assertEquals(emptyList<String>(), r.ids(Placement.PANEL, ShellScope.APP))
    }

    @Test
    fun `extension containers follow core in registration order and leave with their pack`() {
        val r = CoreShell.containers()
            .register(spec("acme.docker.main"), docker).registry
            .register(spec("acme.docker.logs", Placement.PANEL), docker).registry
        assertEquals(listOf("explorer", "search", "scm", "acme.docker.main"), r.ids(Placement.SIDEBAR, ShellScope.WORKSPACE))
        assertEquals("acme.docker.logs", r.ids(Placement.PANEL, ShellScope.WORKSPACE).last())
        assertEquals(listOf("home.projects", "extensions.list", "settings.categories", "acme.docker.main"), r.ids(Placement.SIDEBAR, ShellScope.APP))
        val gone = r.unregister("acme.docker")
        assertNull(gone.byId("acme.docker.main"))
        assertEquals(CoreShell.containers().ids(Placement.PANEL, ShellScope.WORKSPACE), gone.ids(Placement.PANEL, ShellScope.WORKSPACE))
    }

    @Test
    fun `ids collide by the core wins and first registration rules`() {
        val base = CoreShell.containers().register(spec("acme.docker.main"), docker).registry
        val cases = listOf(
            Triple("core id", spec("explorer") to docker, RejectReason.DUPLICATE_ID),
            Triple("own id", spec("acme.docker.main") to docker, RejectReason.DUPLICATE_ID),
            Triple("not namespaced", spec("docker") to docker, RejectReason.NOT_NAMESPACED),
            Triple("foreign namespace", spec("acme.other.x") to docker, RejectReason.NOT_NAMESPACED),
        )
        cases.forEach { (name, entry, reason) ->
            val out = base.register(entry.first, entry.second)
            assertEquals(name, listOf(Rejection(entry.first.id, reason)), out.rejections)
            assertSame(name, base, out.registry)
        }
        assertEquals("Files", base.byId("explorer")?.title)
    }

    @Test
    fun `a user placement override applies only where the container allows it`() {
        val r = CoreShell.containers().register(spec("acme.docker.table", locations = setOf(Placement.SIDEBAR, Placement.PANEL)), docker).registry
        val table = requireNotNull(r.byId("acme.docker.table"))
        assertEquals(Placement.SIDEBAR, r.placementOf(table))
        assertEquals(Placement.PANEL, r.placementOf(table, ContainerPrefs(placement = mapOf(table.id to Placement.PANEL))))
        assertEquals(Placement.SIDEBAR, r.placementOf(table, ContainerPrefs(placement = mapOf(table.id to Placement.SECONDARY_SIDEBAR))))
        assertTrue(r.canMove(table.id, Placement.PANEL))
        assertFalse(r.canMove(table.id, Placement.SECONDARY_SIDEBAR))
        assertFalse(r.canMove("missing", Placement.PANEL))
        val moved = ContainerPrefs(placement = mapOf("outline" to Placement.PANEL))
        assertEquals(emptyList<String>(), r.ids(Placement.SECONDARY_SIDEBAR, ShellScope.WORKSPACE, moved))
        assertTrue("outline" in r.ids(Placement.PANEL, ShellScope.WORKSPACE, moved))
    }

    @Test
    fun `the default placement is always allowed even when locations omit it`() {
        val s = spec("x", Placement.PANEL, locations = setOf(Placement.SIDEBAR))
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), s.allowed)
    }

    @Test
    fun `hidden containers are gone everywhere`() {
        val hidden = ContainerPrefs(hidden = setOf("scm"))
        assertEquals(listOf("explorer", "search"), CoreShell.containers().ids(Placement.SIDEBAR, ShellScope.WORKSPACE, hidden))
    }

    @Test
    fun `active falls back to the first container when the wanted one is gone`() {
        val r = CoreShell.containers()
        assertEquals("scm", r.active(Placement.SIDEBAR, "scm", ShellScope.WORKSPACE)?.id)
        assertEquals("explorer", r.active(Placement.SIDEBAR, "acme.uninstalled.main", ShellScope.WORKSPACE)?.id)
        assertEquals("explorer", r.active(Placement.SIDEBAR, null, ShellScope.WORKSPACE)?.id)
        assertEquals("explorer", r.active(Placement.SIDEBAR, "home.projects", ShellScope.WORKSPACE)?.id)
        assertNull(r.active(Placement.PANEL, "terminal", ShellScope.APP))
        assertEquals("problems", r.active(Placement.PANEL, "terminal", ShellScope.WORKSPACE, ContainerPrefs(hidden = setOf("terminal")))?.id)
    }

    @Test
    fun `placement names round trip`() {
        Placement.entries.forEach { assertEquals(it, Placement.ofWire(it.wire)) }
        assertNull(Placement.ofWire("left"))
    }
}
