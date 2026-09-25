package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.act
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.host.AppRenderers
import dev.easyide.app.ui.shell.workspaceShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePanelsTest {
    private val registries = AppDocuments.registries { "t" }.forWorkspace()
    private val bound = WorkspacePanels.containerIds + AppRenderers.containerIds

    @Test fun `every container a workspace navigation item leads to has a renderer`() {
        WorkspaceNav.items(registries.navigation, registries.containers, dev.easyide.app.ui.shell.NavPrefs()) { true }
            .mapNotNull { (it.target as? NavTarget.Container)?.id }
            .forEach { assertTrue(it, it in bound) }
    }

    @Test fun `every container in a workspace placement that has a renderer belongs to the workspace`() {
        Placement.entries.flatMap { registries.containers.inPlacement(it, ShellScope.WORKSPACE) }.map { it.id }
            .filter { it in WorkspacePanels.containerIds }
            .forEach { assertTrue(it, registries.containers.byId(it)!!.scope.includes(ShellScope.WORKSPACE)) }
    }

    @Test fun `the workspace panels are the ones the layout presets name`() {
        listOf(CoreShell.EXPLORER, CoreShell.SOURCE_CONTROL, CoreShell.OUTLINE, CoreShell.TERMINAL, CoreShell.PROBLEMS_PANEL, CoreShell.SEARCH_PANEL)
            .forEach { assertTrue(it, it in WorkspacePanels.containerIds) }
    }

    @Test fun `showing a panel marks its navigation item and never collapses`() {
        val show = ShellAction.ShowPanel(Placement.PANEL, CoreShell.TERMINAL, CoreShell.navIdOf(CoreShell.TERMINAL))
        val once = workspaceShell(EXPANDED).act(show)
        assertEquals(CoreShell.TERMINAL_NAV, once.current.nav)
        assertTrue(once.act(show).current.layout.isOpen(Placement.PANEL))
    }

    @Test fun `every core container that has a nav item resolves its nav id`() {
        assertEquals(CoreShell.FILES, CoreShell.navIdOf(CoreShell.EXPLORER))
        assertEquals(CoreShell.SETTINGS, CoreShell.navIdOf(CoreShell.SETTINGS_CATEGORIES))
        assertEquals(null, CoreShell.navIdOf(CoreShell.OUTPUT))
    }
}
