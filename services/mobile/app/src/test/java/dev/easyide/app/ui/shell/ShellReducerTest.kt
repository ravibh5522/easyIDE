package dev.easyide.app.ui.shell

import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellReducerTest {
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val c = file("c.kt")
    private val settingsEditor = uri("easyide://settings/editor")

    @Test
    fun `a fresh shell is app scope on Home`() {
        val s = ShellState()
        assertEquals(ShellScope.APP, s.scope)
        assertEquals(CoreShell.HOME, s.current.nav)
        assertEquals(CoreShell.HOME_PROJECTS, s.current.layout.container(Placement.SIDEBAR))
        assertTrue(s.current.layout.isOpen(Placement.SIDEBAR))
    }

    @Test
    fun `opening a workspace swaps the scope and leaving returns to app scope`() {
        val app = ShellState(window = EXPANDED)
        val ws = app.act(ShellAction.OpenWorkspace(ScopeState.workspace(app.arrangement)))
        assertEquals(ShellScope.WORKSPACE, ws.scope)
        assertEquals(CoreShell.FILES, ws.current.nav)
        assertSame(app.app, ws.app)
        assertEquals(app, ws.act(ShellAction.LeaveWorkspace))
    }

    @Test
    fun `a new workspace starts from the auto preset of its arrangement with one empty group`() {
        val cases = listOf(
            "compact" to (COMPACT to emptySet<Placement>()),
            "medium" to (MEDIUM to setOf(Placement.SIDEBAR)),
            "expanded" to (EXPANDED to Placement.entries.toSet()),
            "book" to (BOOK to setOf(Placement.SIDEBAR, Placement.PANEL)),
            "tabletop" to (TABLETOP to setOf(Placement.PANEL)),
        )
        cases.forEach { (name, case) ->
            val (window, panels) = case
            val s = workspaceShell(window)
            assertEquals(name, panels, s.openPanels())
            assertEquals(name, 1, s.current.stage.groups.size)
            assertEquals(name, LayoutPresets.AUTO, s.current.layout.preset)
        }
        assertEquals(CoreShell.EXPLORER, workspaceShell(EXPANDED).current.layout.container(Placement.SIDEBAR))
        assertEquals(CoreShell.OUTLINE, workspaceShell(EXPANDED).current.layout.container(Placement.SECONDARY_SIDEBAR))
    }

    @Test
    fun `tapping the active navigation item collapses its panel and tapping again reopens it`() {
        val s = workspaceShell(EXPANDED)
        val collapsed = s.act(FILES_NAV)
        assertFalse(collapsed.current.layout.isOpen(Placement.SIDEBAR))
        assertTrue(collapsed.act(FILES_NAV).current.layout.isOpen(Placement.SIDEBAR))
        assertEquals(CoreShell.SEARCH_PANEL, s.act(SEARCH_NAV).current.layout.container(Placement.SIDEBAR))
        assertTrue(s.act(SEARCH_NAV).current.layout.isOpen(Placement.SIDEBAR))
        assertEquals(CoreShell.SEARCH, s.act(SEARCH_NAV).current.nav)
    }

    @Test
    fun `compact shows one panel at a time`() {
        val s = workspaceShell(COMPACT).act(FILES_NAV)
        assertEquals(setOf(Placement.SIDEBAR), s.openPanels())
        val terminal = s.act(TERMINAL_NAV)
        assertEquals(setOf(Placement.PANEL), terminal.openPanels())
        assertEquals(setOf(Placement.SIDEBAR), terminal.act(FILES_NAV).openPanels())
    }

    @Test
    fun `medium docks one side panel at a time`() {
        val s = workspaceShell(MEDIUM)
        assertEquals(setOf(Placement.SIDEBAR), s.openPanels())
        val right = s.act(ShellAction.TogglePanel(Placement.SECONDARY_SIDEBAR))
        assertEquals(setOf(Placement.SECONDARY_SIDEBAR), right.openPanels())
        assertEquals(setOf(Placement.SECONDARY_SIDEBAR, Placement.PANEL), right.act(ShellAction.TogglePanel(Placement.PANEL)).openPanels())
    }

    @Test
    fun `opening a document from a compact overlay closes the overlay`() {
        val s = workspaceShell(COMPACT).act(FILES_NAV)
        assertEquals(setOf(Placement.SIDEBAR), s.openPanels())
        val opened = s.act(open(a))
        assertEquals(emptySet<Placement>(), opened.openPanels())
        assertEquals(listOf(listOf("a.kt")), opened.tabNames())
    }

    @Test
    fun `a background open leaves the overlay alone`() {
        val s = workspaceShell(COMPACT).act(FILES_NAV).act(open(a, OpenOptions(focus = false)))
        assertEquals(setOf(Placement.SIDEBAR), s.openPanels())
    }

    @Test
    fun `docked panels stay open when a document opens`() {
        assertEquals(Placement.entries.toSet(), workspaceShell(EXPANDED).act(open(a)).openPanels())
        assertEquals(setOf(Placement.SIDEBAR), workspaceShell(PHONE_LANDSCAPE).act(open(a)).openPanels())
    }

    @Test
    fun `phone landscape docks the side panel but the bottom panel is an overlay that a document dismisses`() {
        val s = workspaceShell(PHONE_LANDSCAPE).act(TERMINAL_NAV)
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), s.openPanels())
        assertEquals(setOf(Placement.SIDEBAR), s.act(open(a)).openPanels())
    }

    @Test
    fun `app scope on a phone pushes a document over its list and remembers the selection`() {
        val s = ShellState().act(SETTINGS_NAV)
        assertFalse(s.pushed)
        val pushed = s.act(open(settingsEditor, OpenOptions(preview = true)))
        assertTrue(pushed.pushed)
        assertEquals(settingsEditor, pushed.current.selection[CoreShell.SETTINGS])
        assertFalse(pushed.act(HOME_NAV).pushed)
    }

    @Test
    fun `selecting an app destination brings back its last document without pushing on a phone`() {
        val s = ShellState().act(SETTINGS_NAV, open(settingsEditor, OpenOptions(preview = true)), HOME_NAV, SETTINGS_NAV)
        assertFalse(s.pushed)
        assertEquals(listOf(listOf("editor")), s.tabNames())
        assertEquals(settingsEditor, s.current.stage.activeGroup.active)
        val wide = ShellState(window = EXPANDED).act(SETTINGS_NAV, open(settingsEditor, OpenOptions(preview = true)), HOME_NAV)
        assertEquals(CoreShell.HOME, wide.current.nav)
        assertEquals(settingsEditor, wide.act(SETTINGS_NAV).current.stage.activeGroup.active)
    }

    @Test
    fun `an app document opened while the list shows starts a fresh history`() {
        val s = ShellState()
            .act(SETTINGS_NAV, open(settingsEditor), open(uri("easyide://settings/terminal")))
        assertEquals(listOf(settingsEditor), s.current.stage.activeGroup.history.back)
        val second = s.act(HOME_NAV, SETTINGS_NAV, open(uri("easyide://settings/git")))
        assertEquals(NavHistory.EMPTY, second.current.stage.activeGroup.history)
    }

    @Test
    fun `stage actions edit the current scope only`() {
        val s = workspaceShell(EXPANDED).act(open(a), open(b, OpenOptions(group = GroupTarget.BESIDE)))
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt")), s.tabNames())
        assertEquals(EditorStage(), s.app.stage)
        val cases = listOf(
            "pin" to ShellAction.Pin(0, a),
            "unpin" to ShellAction.Unpin(0, a),
            "keep" to ShellAction.Keep(0, a),
            "reorder" to ShellAction.Reorder(0, a, 0),
            "activate" to ShellAction.Activate(0, a),
            "focus" to ShellAction.FocusGroup(0),
        )
        cases.forEach { (name, action) -> assertEquals(name, 2, s.act(action).current.stage.groups.size) }
        assertEquals(TabState.PINNED, s.act(ShellAction.Pin(0, a)).current.stage.groups[0].tabs.single().state)
        assertEquals(0, s.act(ShellAction.FocusGroup(0)).current.stage.active)
        assertEquals(listOf(listOf("a.kt")), s.act(ShellAction.Close(1, b)).tabNames())
        assertEquals(listOf(listOf("a.kt")), s.act(ShellAction.CloseMany(1, b, CloseScope.ALL)).tabNames())
        assertEquals(listOf(listOf("a.kt", "b.kt")), s.act(ShellAction.Move(1, b, 0)).tabNames())
        assertEquals(listOf(listOf("a.kt", "b.kt")), s.act(ShellAction.Unsplit(1)).tabNames())
        assertEquals(listOf(listOf("b.kt")), s.act(ShellAction.CloseWhere { it == a }).tabNames())
    }

    @Test
    fun `split follows the arrangement's capacity`() {
        assertEquals(1, workspaceShell(COMPACT).act(ShellAction.Split).current.stage.groups.size)
        assertEquals(2, workspaceShell(MEDIUM).act(ShellAction.Split, ShellAction.Split).current.stage.groups.size)
        assertEquals(4, workspaceShell(EXPANDED).act(*Array(6) { ShellAction.Split }).current.stage.groups.size)
    }

    @Test
    fun `beside on a phone opens in the one group`() {
        val s = workspaceShell(COMPACT).act(open(a), open(b, OpenOptions(group = GroupTarget.BESIDE)))
        assertEquals(listOf(listOf("a.kt", "b.kt")), s.tabNames())
    }

    @Test
    fun `resizing across arrangements saves and restores each layout and never loses documents`() {
        val wide = workspaceShell(EXPANDED)
            .act(open(a), open(b, OpenOptions(group = GroupTarget.BESIDE)), open(c, OpenOptions(group = GroupTarget.BESIDE)))
            .act(ShellAction.TogglePanel(Placement.SECONDARY_SIDEBAR))
        val docs = wide.current.stage.documents.toSet()
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), wide.openPanels())

        val phone = wide.act(ShellAction.Resize(COMPACT))
        assertEquals(PaneArrangement.SINGLE_PANE, phone.arrangement)
        assertEquals(emptySet<Placement>(), phone.openPanels())
        assertEquals(1, phone.current.stage.groups.size)
        assertEquals(docs, phone.current.stage.documents.toSet())
        assertEquals(wide.current.layout, phone.current.saved.getValue(PaneArrangement.FULL))

        val back = phone.act(ShellAction.Resize(EXPANDED))
        assertEquals(wide.current.layout, back.current.layout)
        assertEquals(docs, back.current.stage.documents.toSet())
        assertEquals(1, back.current.stage.groups.size)
    }

    @Test
    fun `a chosen preset that is not offered in the new arrangement falls back to auto`() {
        val wide = workspaceShell(EXPANDED).act(ShellAction.ApplyPreset("workbench"))
        assertEquals("workbench", wide.current.layout.preset)
        val phone = wide.act(ShellAction.Resize(COMPACT))
        assertEquals("workbench", phone.current.layout.preset)
        assertEquals(emptySet<Placement>(), phone.openPanels())
        val medium = wide.act(ShellAction.Resize(MEDIUM))
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), medium.openPanels())
    }

    @Test
    fun `postures are arrangements with their own rules`() {
        val flat = workspaceShell(EXPANDED)
        val book = flat.act(ShellAction.Resize(BOOK))
        assertEquals(PaneArrangement.BOOK, book.arrangement)
        assertTrue(book.current.layout.isOpen(Placement.SIDEBAR))
        assertEquals(2, ShellLimits.groupCapacity(book.arrangement))
        val tabletop = book.act(ShellAction.Resize(TABLETOP))
        assertTrue(tabletop.current.layout.isOpen(Placement.PANEL))
        assertEquals(book.current.layout, tabletop.act(ShellAction.Resize(BOOK)).current.layout)
        assertEquals(flat.current.layout, tabletop.act(ShellAction.Resize(EXPANDED)).current.layout)
    }

    @Test
    fun `resizing within one arrangement keeps the layout but enforces the rule`() {
        val s = workspaceShell(EXPANDED)
        assertEquals(s.current.layout, s.act(ShellAction.Resize(EXPANDED.copy(height = HeightClass.COMPACT))).current.layout)
    }

    @Test
    fun `app scope keeps its layout across arrangements`() {
        val s = ShellState(window = EXPANDED).act(SETTINGS_NAV)
        val phone = s.act(ShellAction.Resize(COMPACT))
        assertEquals(CoreShell.SETTINGS_CATEGORIES, phone.current.layout.container(Placement.SIDEBAR))
        assertEquals(CoreShell.SETTINGS_CATEGORIES, phone.act(ShellAction.Resize(EXPANDED)).current.layout.container(Placement.SIDEBAR))
    }

    @Test
    fun `resizing reaches the hidden app scope while a workspace is open`() {
        val s = workspaceShell(EXPANDED).act(ShellAction.Resize(COMPACT))
        assertEquals(PaneArrangement.SINGLE_PANE, s.arrangement)
        assertTrue(s.app.saved.containsKey(PaneArrangement.FULL))
    }

    @Test
    fun `presets shape panels containers and groups per arrangement`() {
        val s = workspaceShell(EXPANDED).act(open(a), open(b))
        val focus = s.act(ShellAction.ApplyPreset("focus"))
        assertEquals(emptySet<Placement>(), focus.openPanels())
        assertEquals("focus", focus.current.layout.preset)
        val classic = s.act(ShellAction.ApplyPreset("classic"))
        assertEquals(setOf(Placement.SIDEBAR), classic.openPanels())
        val tf = s.act(ShellAction.ApplyPreset("terminalFirst"))
        assertEquals(setOf(Placement.PANEL), tf.openPanels())
        val workbench = s.act(ShellAction.ApplyPreset("workbench"))
        assertEquals(2, workbench.current.stage.groups.size)
        assertEquals(setOf(a, b), workbench.current.stage.documents.toSet())
        assertEquals(1, workbench.act(ShellAction.ApplyPreset("focus")).current.stage.groups.size)
        assertEquals(setOf(a, b), workbench.act(ShellAction.ApplyPreset("focus")).current.stage.documents.toSet())
    }

    @Test
    fun `presets apply under the window rule`() {
        val medium = workspaceShell(MEDIUM).act(ShellAction.ApplyPreset("workbench"))
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), medium.openPanels())
        assertEquals(2, medium.current.stage.groups.size)
    }

    @Test
    fun `presets not offered here unknown ones and app scope leave the state alone`() {
        val s = workspaceShell(EXPANDED)
        assertSame(s.current, s.act(ShellAction.ApplyPreset("book")).current)
        assertSame(s.current, s.act(ShellAction.ApplyPreset("nope")).current)
        assertEquals(ShellState().app, ShellState().act(ShellAction.ApplyPreset("focus")).app)
        assertEquals("auto", s.act(ShellAction.ApplyPreset("auto")).current.layout.preset)
    }

    @Test
    fun `an extension preset is offered through the environment`() {
        val review = LayoutPreset(
            id = "acme.agent.review", title = "Agent review",
            containers = mapOf(Placement.SECONDARY_SIDEBAR to "acme.agent.context"),
            open = setOf(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR),
            groups = 2,
            arrangements = setOf(PaneArrangement.FULL),
        )
        val env = ShellEnv(::typeOf, listOf(review))
        val s = ShellReducer.reduce(workspaceShell(EXPANDED), ShellAction.ApplyPreset("acme.agent.review"), env)
        assertEquals("acme.agent.review", s.current.layout.preset)
        assertEquals("acme.agent.context", s.current.layout.container(Placement.SECONDARY_SIDEBAR))
        assertEquals(setOf(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR), s.openPanels())
        assertNull(ShellReducer.reduce(workspaceShell(MEDIUM), ShellAction.ApplyPreset("acme.agent.review"), env).current.layout.container(Placement.SECONDARY_SIDEBAR))
    }

    @Test
    fun `offered presets depend on the arrangement`() {
        fun ids(a: PaneArrangement) = LayoutPresets.offered(a).map { it.id }
        assertEquals(listOf("focus", "classic", "terminalFirst"), ids(PaneArrangement.SINGLE_PANE))
        assertEquals(listOf("focus", "classic", "workbench", "terminalFirst"), ids(PaneArrangement.ONE_SIDE))
        assertEquals(listOf("focus", "classic", "workbench", "terminalFirst"), ids(PaneArrangement.FULL))
        assertEquals(listOf("focus", "classic", "terminalFirst", "book"), ids(PaneArrangement.BOOK))
        assertEquals(listOf("focus", "classic", "terminalFirst", "tabletop"), ids(PaneArrangement.TABLETOP))
        PaneArrangement.entries.forEach { assertTrue(LayoutPresets.auto(it).appliesTo(it)) }
    }

    @Test
    fun `interaction clears navigation focus but a resize does not`() {
        val focused = workspaceShell(COMPACT).copy(navFocused = true)
        assertTrue(focused.act(ShellAction.Resize(COMPACT)).navFocused)
        assertFalse(focused.act(open(a)).navFocused)
        assertFalse(focused.act(FILES_NAV).navFocused)
        assertFalse(focused.act(ShellAction.TogglePanel(Placement.PANEL)).navFocused)
    }
}
