package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.nav.NavIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNavTest {
    private val registries = AppDocuments.registries { "t" }.forWorkspace()
    private fun items(prefs: NavPrefs = NavPrefs(), navigation: dev.easyide.app.ui.shell.NavRegistry = registries.navigation) =
        WorkspaceNav.items(navigation, registries.containers, prefs) { true }

    @Test fun `everything the old rail reached is a workspace destination, in reach order`() {
        assertEquals(
            listOf("files", "search", "git", "terminal", "problems", "outline", "extensions", "settings", "commands", "projects", "close-project"),
            items().map { it.id },
        )
    }

    @Test fun `a phone's bar keeps files, search, git and terminal beside More`() {
        assertEquals(listOf("files", "search", "git", "terminal"), items().take(4).map { it.id })
    }

    @Test fun `home is app scope only`() {
        assertTrue(items().none { it.id == CoreShell.HOME })
    }

    @Test fun `container items lead to registered containers and command items to known commands`() {
        items().forEach { item ->
            when (val t = item.target) {
                is NavTarget.Container -> assertTrue(item.id, registries.containers.byId(t.id) != null)
                is NavTarget.Command -> assertTrue(item.id, t.id in WorkspaceNav.COMMANDS)
            }
        }
    }

    @Test fun `every item has an icon that is not the fallback`() {
        val fallback = NavIcons.of(IconRef("no-such-glyph"))
        items().forEach { assertTrue(it.id, NavIcons.of(it.icon) !== fallback) }
    }

    @Test fun `an item that names an unknown command is dropped`() {
        val bad = NavItem("bad", "Bad", IconRef("files"), NavTarget.Command("nope"), 5, ScopeFilter.WORKSPACE)
        val nav = registries.navigation.register(bad, Origin.Core).registry
        assertTrue(items(navigation = nav).none { it.id == "bad" })
    }

    @Test fun `the user's hidden and order settings apply to the workspace surface`() {
        val prefs = NavPrefs(order = listOf("git"), hidden = setOf("outline"))
        val ids = items(prefs).map { it.id }
        assertEquals("git", ids.first())
        assertTrue("outline" !in ids)
    }

    @Test fun `extensions and settings still lead the app scope in their order`() {
        val app = registries.navigation.visible(ShellScope.APP, NavPrefs(), dev.easyide.app.ui.shell.NavEnv({ true }, { true })).map { it.id }
        assertEquals(listOf("home", "extensions", "settings"), app)
    }
}
