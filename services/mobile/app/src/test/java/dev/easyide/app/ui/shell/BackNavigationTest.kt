package dev.easyide.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackNavigationTest {
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val settingsEditor = uri("easyide://settings/editor")
    private val settingsFonts = settingsEditor.withFragment("fonts")

    private fun ShellState.back(context: BackContext = BackContext()) = BackNavigation.back(this, context)

    /** Presses Back until the system would handle it, returning the steps taken. */
    private fun ShellState.drain(context: BackContext = BackContext()): List<BackStep> {
        val steps = ArrayList<BackStep>()
        var s = this
        while (steps.size < 20) {
            val r = s.back(context)
            steps += r.step
            if (r.step == BackStep.SYSTEM || r.step == BackStep.CONFIRM_UNSAVED) break
            s = r.state
        }
        return steps
    }

    @Test
    fun `step 1 an open transient takes Back first and changes nothing`() {
        val s = workspaceShell(COMPACT).act(FILES_NAV)
        val r = s.back(BackContext(transientOpen = true))
        assertEquals(BackStep.CLOSE_TRANSIENT, r.step)
        assertSame(s, r.state)
    }

    @Test
    fun `step 2 a compact overlay panel closes before anything else`() {
        val s = workspaceShell(COMPACT).act(open(a), open(b), FILES_NAV)
        val r = s.back()
        assertEquals(BackStep.CLOSE_PANEL, r.step)
        assertEquals(emptySet<Placement>(), r.state.openPanels())
        assertEquals(s.current.stage, r.state.current.stage)
    }

    @Test
    fun `step 3 steps back through the active group's history without closing tabs`() {
        val s = workspaceShell(EXPANDED).act(open(a), open(b))
        val r = s.back()
        assertEquals(BackStep.HISTORY_BACK, r.step)
        assertEquals(a, r.state.current.stage.activeGroup.active)
        assertEquals(listOf(listOf("a.kt", "b.kt")), r.state.tabNames())
    }

    @Test
    fun `a sub page step is a history step too`() {
        val s = ShellState(window = EXPANDED).act(SETTINGS_NAV, open(settingsEditor), open(settingsFonts))
        val r = s.back()
        assertEquals(BackStep.HISTORY_BACK, r.step)
        assertEquals(settingsEditor, r.state.current.stage.activeGroup.activeTab?.uri)
    }

    @Test
    fun `phone flow in the workspace panel then history then navigation then leave`() {
        val s = workspaceShell(COMPACT).act(open(a), open(b), FILES_NAV)
        assertEquals(
            listOf(BackStep.CLOSE_PANEL, BackStep.HISTORY_BACK, BackStep.FOCUS_NAV, BackStep.LEAVE_WORKSPACE, BackStep.FOCUS_NAV, BackStep.SYSTEM),
            s.drain(),
        )
    }

    @Test
    fun `tablet workspace has no navigation focus step`() {
        val s = workspaceShell(EXPANDED).act(open(a))
        assertEquals(listOf(BackStep.LEAVE_WORKSPACE, BackStep.SYSTEM), s.drain())
    }

    @Test
    fun `step 5 sets navigation focus once and any interaction resets it`() {
        val s = workspaceShell(COMPACT)
        val first = s.back()
        assertEquals(BackStep.FOCUS_NAV, first.step)
        assertTrue(first.state.navFocused)
        assertEquals(BackStep.LEAVE_WORKSPACE, first.state.back().step)
        assertFalse(first.state.act(open(a)).navFocused)
    }

    @Test
    fun `step 6 leaving the workspace is guarded by unsaved documents`() {
        val s = workspaceShell(EXPANDED).act(open(a))
        val guarded = s.back(BackContext(hasUnsaved = true))
        assertEquals(BackStep.CONFIRM_UNSAVED, guarded.step)
        assertSame(s, guarded.state)
        assertEquals(ShellScope.WORKSPACE, guarded.state.scope)
        val left = s.back()
        assertEquals(BackStep.LEAVE_WORKSPACE, left.step)
        assertEquals(ShellScope.APP, left.state.scope)
        assertFalse(left.state.pushed)
    }

    @Test
    fun `unsaved documents do not guard the earlier steps`() {
        val s = workspaceShell(COMPACT).act(open(a), open(b), FILES_NAV)
        val steps = s.drain(BackContext(hasUnsaved = true))
        assertEquals(listOf(BackStep.CLOSE_PANEL, BackStep.HISTORY_BACK, BackStep.FOCUS_NAV, BackStep.CONFIRM_UNSAVED), steps)
    }

    @Test
    fun `phone app scope pops a pushed document then goes home then to the system`() {
        val s = ShellState().act(SETTINGS_NAV, open(settingsEditor, OpenOptions(preview = true)))
        assertTrue(s.pushed)
        val popped = s.back()
        assertEquals(BackStep.POP_TO_LIST, popped.step)
        assertFalse(popped.state.pushed)
        assertEquals(
            listOf(BackStep.POP_TO_LIST, BackStep.FOCUS_NAV, BackStep.GO_HOME, BackStep.FOCUS_NAV, BackStep.SYSTEM),
            s.drain(),
        )
    }

    @Test
    fun `phone history inside a pushed document goes before the pop`() {
        val s = ShellState().act(SETTINGS_NAV, open(settingsEditor), open(settingsFonts))
        assertEquals(listOf(BackStep.HISTORY_BACK, BackStep.POP_TO_LIST), s.drain().take(2))
    }

    @Test
    fun `opening from a list twice does not make Back walk between the two documents`() {
        val popped = ShellState().act(SETTINGS_NAV, open(settingsEditor), HOME_NAV, SETTINGS_NAV, open(uri("easyide://settings/git")))
        assertEquals(BackStep.POP_TO_LIST, popped.back().step)
    }

    @Test
    fun `going home returns the primary panel to the Home list`() {
        val s = ShellState(window = EXPANDED).act(SETTINGS_NAV)
        val r = s.back()
        assertEquals(BackStep.GO_HOME, r.step)
        assertEquals(CoreShell.HOME, r.state.current.nav)
        assertEquals(CoreShell.HOME_PROJECTS, r.state.current.layout.container(Placement.SIDEBAR))
        assertTrue(r.state.current.layout.isOpen(Placement.SIDEBAR))
        assertEquals(BackStep.SYSTEM, r.state.back().step)
    }

    @Test
    fun `Home hands Back to the system on a tablet`() {
        val r = ShellState(window = EXPANDED).back()
        assertEquals(BackStep.SYSTEM, r.step)
    }

    @Test
    fun `a bottom panel overlay on a short window closes but docked side panels stay`() {
        val s = workspaceShell(PHONE_LANDSCAPE).act(TERMINAL_NAV)
        assertEquals(setOf(Placement.SIDEBAR, Placement.PANEL), s.openPanels())
        val r = s.back()
        assertEquals(BackStep.CLOSE_PANEL, r.step)
        assertEquals(setOf(Placement.SIDEBAR), r.state.openPanels())
        assertEquals(BackStep.LEAVE_WORKSPACE, r.state.back().step)
    }

    @Test
    fun `Back never closes a tab`() {
        val s = workspaceShell(COMPACT).act(open(a), open(b), open(file("c.kt")))
        val afterHistory = s.back().state.back().state
        assertEquals(3, afterHistory.current.stage.documents.size)
        assertEquals(ShellScope.WORKSPACE, afterHistory.scope)
        var cur = afterHistory
        repeat(10) { cur = cur.back().state }
        assertEquals(ShellScope.APP, cur.scope)
    }
}
