package dev.easyide.app.ui.shell.host

import dev.easyide.app.data.ShellStorage
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.COMPACT
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.ShellSnapshot
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.shell.nav.NavBadgeValue
import dev.easyide.app.ui.shell.nav.NavContribution
import dev.easyide.app.ui.shell.nav.NavItemSource
import dev.easyide.app.ui.shell.nav.NavSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private class FakeStorage(var text: String? = null) : ShellStorage {
        val writes = mutableListOf<String>()
        override suspend fun read(): String? = text
        override suspend fun write(snapshot: String) {
            writes += snapshot
            text = snapshot
        }
    }

    private class FakeSource : NavItemSource {
        override val contributions = MutableStateFlow<List<NavContribution>>(emptyList())
        override val badges: StateFlow<Map<String, NavBadgeValue>> = MutableStateFlow(emptyMap())
        override fun holds(condition: String) = false
    }

    private val dockerContainer = ContainerSpec("acme.docker.panel", "Docker", IconRef("x"), Placement.SIDEBAR, ScopeFilter.APP)
    private val dockerNav = NavItem("acme.docker.nav", "Docker", IconRef("x"), NavTarget.Container("acme.docker.panel"), 100, ScopeFilter.APP)

    private fun registries(): AppRegistries {
        val base = AppDocuments.registries { "text" }
        return AppRegistries(base.documents, base.containers.register(dockerContainer, Origin.Extension("acme.docker")).registry, base.navigation)
    }

    private fun TestScope.vm(
        storage: FakeStorage = FakeStorage(),
        source: FakeSource = FakeSource(),
        settings: MutableStateFlow<NavSettings> = MutableStateFlow(NavSettings()),
    ) = ShellViewModel(storage, registries(), source, settings, backgroundScope, debounceMs = DEBOUNCE)

    private fun ShellViewModel.item(id: String) = navItems.value.first { it.id == id }

    /** A ready shell in [window]. */
    private fun TestScope.ready(window: dev.easyide.app.ui.foundation.WindowSize, storage: FakeStorage = FakeStorage()): ShellViewModel =
        vm(storage).also { it.onWindow(window); runCurrent() }

    @Test fun `the state is empty until the window is known and restored`() = runTest {
        val shell = vm()
        assertNull(shell.state.value)
        shell.onWindow(COMPACT)
        runCurrent()
        assertEquals(CoreShell.HOME, shell.state.value!!.current.nav)
    }

    @Test fun `a saved destination and its page come back`() = runTest {
        val page = AppDocuments.settingsPage("editor")
        val saved = ShellState(window = EXPANDED).let {
            ShellSnapshot.encodeApp(ScopeState.app().copy(nav = CoreShell.SETTINGS, selection = mapOf(CoreShell.SETTINGS to page)), it.arrangement)
        }
        val shell = ready(EXPANDED, FakeStorage(saved))
        val app = shell.state.value!!.app
        assertEquals(CoreShell.SETTINGS, app.nav)
        assertEquals(page, app.stage.activeGroup.activeTab?.uri)
    }

    @Test fun `an unreadable snapshot starts from Home`() = runTest {
        val shell = ready(COMPACT, FakeStorage("{not json"))
        assertEquals(CoreShell.HOME, shell.state.value!!.current.nav)
        assertFalse(shell.state.value!!.pushed)
    }

    @Test fun `selecting an item selects its container`() = runTest {
        val shell = ready(EXPANDED)
        shell.selectNav(shell.item(CoreShell.SETTINGS))
        val scope = shell.state.value!!.current
        assertEquals(CoreShell.SETTINGS, scope.nav)
        assertEquals(CoreShell.SETTINGS_CATEGORIES, scope.layout.container(Placement.SIDEBAR))
    }

    @Test fun `goTo selects a core destination by id and ignores an unknown one`() = runTest {
        val shell = ready(COMPACT)
        shell.goTo(CoreShell.EXTENSIONS)
        assertEquals(CoreShell.EXTENSIONS, shell.state.value!!.current.nav)
        shell.goTo("nope")
        assertEquals(CoreShell.EXTENSIONS, shell.state.value!!.current.nav)
    }

    @Test fun `on a phone opening a page pushes it and Back walks out in order`() = runTest {
        val shell = ready(COMPACT)
        shell.goTo(CoreShell.SETTINGS)
        shell.open(AppDocuments.settingsPage("editor"))
        assertTrue(shell.state.value!!.pushed)
        assertEquals(BackStep.POP_TO_LIST, shell.back())
        assertFalse(shell.state.value!!.pushed)
        assertEquals(BackStep.FOCUS_NAV, shell.back())
        assertTrue(shell.state.value!!.navFocused)
        assertEquals(BackStep.GO_HOME, shell.back())
        assertEquals(CoreShell.HOME, shell.state.value!!.current.nav)
    }

    @Test fun `notifications show one at a time and move on when dismissed`() = runTest {
        val shell = ready(COMPACT)
        shell.notify("a")
        shell.notify("b")
        runCurrent()
        assertEquals("a", shell.toast.value)
        shell.toastDismissed()
        runCurrent()
        assertEquals("b", shell.toast.value)
        shell.toastDismissed()
        runCurrent()
        assertNull(shell.toast.value)
    }

    @Test fun `Back from Home on a wide window is left to the system`() = runTest {
        val shell = ready(EXPANDED)
        assertEquals(BackStep.SYSTEM, shell.back())
    }

    @Test fun `Back before the shell is ready is left to the system`() = runTest {
        assertEquals(BackStep.SYSTEM, vm().back())
    }

    @Test fun `resizing the window keeps the open pages`() = runTest {
        val shell = ready(COMPACT)
        shell.goTo(CoreShell.SETTINGS)
        val page = AppDocuments.settingsPage("editor")
        shell.open(page)
        shell.onWindow(EXPANDED)
        val state = shell.state.value!!
        assertEquals(EXPANDED, state.window)
        assertEquals(page, state.current.stage.activeGroup.activeTab?.uri)
    }

    @Test fun `a dragged panel width is applied and reset`() = runTest {
        val shell = ready(EXPANDED)
        shell.resizePane(Pane.EXPLORER, 320f)
        assertEquals(320f, shell.state.value!!.current.layout.sizes.explorer)
        shell.resizePane(Pane.EXPLORER, null)
        assertNull(shell.state.value!!.current.layout.sizes.explorer)
    }

    @Test fun `toggling the primary panel collapses and reopens it`() = runTest {
        val shell = ready(EXPANDED)
        assertTrue(shell.state.value!!.current.layout.isOpen(Placement.SIDEBAR))
        shell.togglePanel(Placement.SIDEBAR)
        assertFalse(shell.state.value!!.current.layout.isOpen(Placement.SIDEBAR))
        shell.togglePanel(Placement.SIDEBAR)
        assertTrue(shell.state.value!!.current.layout.isOpen(Placement.SIDEBAR))
    }

    @Test fun `restoring alone writes nothing`() = runTest {
        val storage = FakeStorage()
        ready(COMPACT, storage)
        advanceTimeBy(DEBOUNCE * 5)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `a burst of changes is written once after the quiet period`() = runTest {
        val storage = FakeStorage()
        val shell = ready(EXPANDED, storage)
        shell.goTo(CoreShell.SETTINGS)
        shell.open(AppDocuments.settingsPage("editor"))
        shell.open(AppDocuments.settingsPage("git"))
        advanceTimeBy(DEBOUNCE - 1)
        assertTrue(storage.writes.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, storage.writes.size)
        val restored = ShellSnapshot.decodeApp(storage.writes.single(), shell.state.value!!.arrangement)!!
        assertEquals(CoreShell.SETTINGS, restored.nav)
        assertEquals(AppDocuments.settingsPage("git"), restored.selection[CoreShell.SETTINGS])
    }

    @Test fun `a change that does not alter the saved form is not written`() = runTest {
        val storage = FakeStorage()
        val shell = ready(COMPACT, storage)
        shell.back() // moves focus to the navigation surface: transient, never persisted
        advanceTimeBy(DEBOUNCE * 5)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `the core destinations show in order`() = runTest {
        assertEquals(listOf(CoreShell.HOME, CoreShell.EXTENSIONS, CoreShell.SETTINGS), vm().navItems.value.map { it.id })
    }

    @Test fun `the user can hide and reorder items`() = runTest {
        val settings = MutableStateFlow(NavSettings())
        val shell = vm(settings = settings)
        settings.value = NavSettings(NavPrefs(order = listOf(CoreShell.SETTINGS), hidden = setOf(CoreShell.EXTENSIONS)))
        runCurrent()
        assertEquals(listOf(CoreShell.SETTINGS, CoreShell.HOME), shell.navItems.value.map { it.id })
    }

    @Test fun `an extension item follows the core ones once its container exists`() = runTest {
        val source = FakeSource()
        val shell = vm(source = source)
        source.contributions.value = listOf(NavContribution("acme.docker", dockerNav))
        runCurrent()
        assertEquals(listOf(CoreShell.HOME, CoreShell.EXTENSIONS, CoreShell.SETTINGS, "acme.docker.nav"), shell.navItems.value.map { it.id })
        shell.onWindow(EXPANDED)
        runCurrent()
        shell.selectNav(shell.item("acme.docker.nav"))
        assertEquals("acme.docker.panel", shell.state.value!!.current.layout.container(Placement.SIDEBAR))
    }

    @Test fun `an extension item whose container is missing or whose clause fails never shows`() = runTest {
        val source = FakeSource()
        val shell = vm(source = source)
        val orphan = dockerNav.copy(id = "acme.docker.orphan", target = NavTarget.Container("acme.docker.gone"))
        val conditional = dockerNav.copy(id = "acme.docker.cond", condition = "workspaceContains:Dockerfile")
        source.contributions.value = listOf(NavContribution("acme.docker", orphan), NavContribution("acme.docker", conditional))
        runCurrent()
        assertEquals(3, shell.navItems.value.size)
        assertNotNull(shell.navItems.value.firstOrNull { it.id == CoreShell.HOME })
    }
}

private const val DEBOUNCE = 400L
