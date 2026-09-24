package dev.easyide.app.ui.shell.host

import dev.easyide.app.data.ShellStorage
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.UriPattern
import dev.easyide.app.ui.shell.ext.ExtContainer
import dev.easyide.app.ui.shell.ext.ExtDocument
import dev.easyide.app.ui.shell.ext.ExtPreset
import dev.easyide.app.ui.shell.ext.ExtShell
import dev.easyide.app.ui.shell.nav.NavBadgeValue
import dev.easyide.app.ui.shell.nav.NavContribution
import dev.easyide.app.ui.shell.nav.NavItemSource
import dev.easyide.app.ui.shell.nav.NavSettings
import dev.easyide.extensions.view.PropValue
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewNode
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.view.ViewType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell view model with extensions folded in: their containers, document types, presets and command items. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelExtensionsTest {
    private class Storage : ShellStorage {
        override suspend fun read(): String? = null
        override suspend fun write(snapshot: String) = Unit
    }

    private class Source : NavItemSource {
        override val contributions = MutableStateFlow<List<NavContribution>>(emptyList())
        override val badges: StateFlow<Map<String, NavBadgeValue>> = MutableStateFlow(emptyMap())
        override var commands: Set<String> = emptySet()
        override fun holds(condition: String) = true
    }

    private val panel = ContainerSpec("acme.demo.main", "Box", IconRef("box"), Placement.SIDEBAR, ScopeFilter.BOTH)
    private val nav = NavItem("acme.demo.nav", "Box", IconRef("box"), NavTarget.Container("acme.demo.main"), 300, ScopeFilter.BOTH)
    private val commandNav = NavItem("acme.demo.cmd", "Open", IconRef("box"), NavTarget.Command("acme.demo.open"), 310, ScopeFilter.BOTH)
    private val type = DocumentType("acme.demo/box", UriPattern("ext", "acme.demo", "box"), { it.name }, { IconRef("box") })
    private val body = ViewDocument(
        "v.json", JsonObject(emptyMap()),
        ViewNode(ViewType.TEXT, null, null, mapOf("value" to PropValue.Text(ViewTemplate.literal("x")))), 1, 1,
    )

    private val ext = ExtShell(
        navigation = listOf(NavContribution("acme.demo", nav), NavContribution("acme.demo", commandNav)),
        containers = listOf(ExtContainer("acme.demo", panel)),
        documents = listOf(ExtDocument("acme.demo", type, body, ViewTemplate.literal("t"), null)),
        presets = listOf(ExtPreset("acme.demo", LayoutPreset("acme.demo.wide", "Wide", mapOf(Placement.SIDEBAR to "acme.demo.main"), setOf(Placement.SIDEBAR)))),
    )

    private fun TestScope.vm(source: Source = Source(), shell: MutableStateFlow<ExtShell> = MutableStateFlow(ext), commands: MutableList<String> = ArrayList()) =
        ShellViewModel(
            Storage(), AppDocuments.registries { "text" }, source, MutableStateFlow(NavSettings()), backgroundScope,
            extensions = shell, runCommand = { commands += it },
        )

    @Test fun `effective registries hold the extension containers, types and items`() = runTest {
        val source = Source().apply { contributions.value = ext.navigation }
        val shell = vm(source)
        runCurrent()
        val r = shell.effective.value
        assertEquals("acme.demo", r.containers.packOf("acme.demo.main"))
        assertEquals("acme.demo/box", r.documents.resolve(DocumentUri.parse("ext://acme.demo/box/n1")!!).id)
        assertNotNull(r.navigation.byId("acme.demo.nav"))
        assertEquals(listOf(LayoutPreset("acme.demo.wide", "Wide", mapOf(Placement.SIDEBAR to "acme.demo.main"), setOf(Placement.SIDEBAR))).map { it.id }, shell.extensionPresets.value.map { it.id })
    }

    @Test fun `selecting an extension container item shows its container, in either scope`() = runTest {
        val source = Source().apply { contributions.value = ext.navigation }
        val shell = vm(source)
        shell.onWindow(EXPANDED)
        runCurrent()
        val item = shell.navItems.value.first { it.id == "acme.demo.nav" }
        shell.selectNav(item)
        assertEquals("acme.demo.main", shell.state.value!!.current.layout.container(Placement.SIDEBAR))
        assertTrue(shell.workspaceNavItems.value.any { it.id == "acme.demo.nav" })
    }

    @Test fun `a command item shows only for a command an extension declared, and runs it`() = runTest {
        val source = Source().apply { contributions.value = ext.navigation }
        val ran = ArrayList<String>()
        val shell = vm(source, commands = ran)
        runCurrent()
        assertTrue(shell.navItems.value.none { it.id == "acme.demo.cmd" })
        source.commands = setOf("acme.demo.open")
        source.contributions.value = ext.navigation.reversed()
        runCurrent()
        val item = shell.navItems.value.first { it.id == "acme.demo.cmd" }
        assertTrue(shell.workspaceNavItems.value.any { it.id == "acme.demo.cmd" })
        shell.selectNav(item)
        runCurrent()
        assertEquals(listOf("acme.demo.open"), ran)
    }

    @Test fun `switching a pack off removes its containers and items again`() = runTest {
        val source = Source().apply { contributions.value = ext.navigation }
        val shell = MutableStateFlow(ext)
        val vm = vm(source, shell)
        runCurrent()
        assertNotNull(vm.effective.value.containers.byId("acme.demo.main"))
        shell.value = ExtShell.EMPTY
        source.contributions.value = emptyList()
        runCurrent()
        assertEquals(null, vm.effective.value.containers.byId("acme.demo.main"))
        assertTrue(vm.navItems.value.none { it.id == "acme.demo.nav" })
        assertEquals(CoreShell.HOME, vm.navItems.value.first().id)
    }
}
