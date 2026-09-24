package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.shell.BOOK
import dev.easyide.app.ui.shell.BackContext
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.COMPACT
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.MEDIUM
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.TABLETOP
import dev.easyide.app.ui.shell.host.AppDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceShellModelTest {
    private val registries = AppDocuments.registries { "t" }.forWorkspace()
    private fun model() = WorkspaceShellModel(registries.documents, registries.containers)
    private fun model(window: WindowSize) = model().also { it.onWindow(window) }

    private fun WorkspaceShellModel.now() = state.value!!
    private fun WorkspaceShellModel.opened(vararg p: Placement) = Placement.entries.filter { now().current.layout.isOpen(it) } == p.toList()
    private fun WorkspaceShellModel.item(id: String) = requireNotNull(WorkspaceNav.items(registries.navigation, registries.containers, dev.easyide.app.ui.shell.NavPrefs()) { true }.firstOrNull { it.id == id })

    @Test fun `nothing exists before the first window`() {
        assertNull(model().state.value)
        assertNull(model().snapshot())
    }

    @Test fun `a fresh project opens with the auto preset of its window`() {
        assertTrue(model(COMPACT).opened())
        assertTrue(model(MEDIUM).opened(Placement.SIDEBAR))
        assertTrue(model(EXPANDED).opened(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR, Placement.PANEL))
        assertEquals(CoreShell.FILES, model(EXPANDED).now().current.nav)
    }

    @Test fun `the stage is a workspace stage with one empty group`() {
        val s = model(COMPACT).now()
        assertEquals(ShellScope.WORKSPACE, s.scope)
        assertEquals(1, s.current.stage.groups.size)
        assertTrue(s.current.stage.documents.isEmpty())
    }

    @Test fun `selecting a container shows it and selecting it again collapses it`() {
        val m = model(EXPANDED)
        m.select(m.item(CoreShell.GIT))
        assertEquals(CoreShell.SOURCE_CONTROL, m.now().current.layout.container(Placement.SIDEBAR))
        assertTrue(m.now().current.layout.isOpen(Placement.SIDEBAR))
        m.select(m.item(CoreShell.GIT))
        assertFalse(m.now().current.layout.isOpen(Placement.SIDEBAR))
    }

    @Test fun `revealing the terminal opens the bottom panel and never collapses it`() {
        val m = model(MEDIUM)
        m.reveal(CoreShell.TERMINAL)
        assertTrue(m.now().current.layout.isOpen(Placement.PANEL))
        m.reveal(CoreShell.TERMINAL)
        assertTrue(m.now().current.layout.isOpen(Placement.PANEL))
        assertEquals(CoreShell.TERMINAL, m.now().current.layout.container(Placement.PANEL))
    }

    @Test fun `on a phone opening the terminal closes the files sheet`() {
        val m = model(COMPACT)
        m.select(m.item(CoreShell.FILES))
        m.reveal(CoreShell.TERMINAL)
        assertTrue(m.opened(Placement.PANEL))
    }

    @Test fun `opening a document from the files sheet on a phone dismisses the sheet`() {
        val m = model(COMPACT)
        m.select(m.item(CoreShell.FILES))
        m.open(FileDocuments.uriOf("a.kt")!!)
        assertTrue(m.opened())
        assertEquals(listOf("a.kt"), m.now().current.stage.documents.mapNotNull { FileDocuments.pathOf(it) })
    }

    @Test fun `extensions and settings are containers that open their pages as documents in the stage`() {
        val m = model(EXPANDED)
        m.select(m.item(CoreShell.SETTINGS))
        assertEquals(CoreShell.SETTINGS_CATEGORIES, m.now().current.layout.container(Placement.SIDEBAR))
        m.open(AppDocuments.settingsPage("editor"))
        m.open(AppDocuments.extensionPage("easyide.python"))
        assertEquals(listOf("easyide://settings/editor", "easyide://extension/easyide.python"), m.now().current.stage.documents.map { it.toString() })
    }

    @Test fun `a pane drag is kept in the layout of its arrangement`() {
        val m = model(EXPANDED)
        m.resizePane(Pane.BOTTOM, 420f)
        assertEquals(420f, m.now().current.layout.sizes.bottom)
    }

    @Test fun `the preset chosen persists and restores per workspace`() {
        val m = model(EXPANDED)
        m.dispatch(ShellAction.ApplyPreset("focus"))
        m.open(AppDocuments.settingsPage("git"))
        val json = requireNotNull(m.snapshot())

        val reopened = model()
        reopened.restore(json)
        reopened.onWindow(EXPANDED)
        val s = reopened.now().current
        assertEquals("focus", s.layout.preset)
        assertTrue(reopened.opened())
        assertEquals(listOf("easyide://settings/git"), s.stage.documents.map { it.toString() })
    }

    @Test fun `a snapshot that arrives after the window is applied in place`() {
        val saved = model(EXPANDED).also { it.dispatch(ShellAction.ApplyPreset("classic")); it.open(AppDocuments.settingsPage("git")) }.snapshot()!!
        val m = model(EXPANDED)
        m.restore(saved)
        assertEquals("classic", m.now().current.layout.preset)
        assertEquals(1, m.now().current.stage.documents.size)
    }

    @Test fun `files the screen opened before the restore landed are kept`() {
        val saved = model(EXPANDED).also { it.open(AppDocuments.settingsPage("git")) }.snapshot()!!
        val m = model(EXPANDED)
        m.open(FileDocuments.uriOf("a.kt")!!)
        m.restore(saved)
        assertEquals(listOf("file:///workspace/a.kt"), m.now().current.stage.documents.map { it.toString() })
    }

    @Test fun `files are never written to the snapshot`() {
        val m = model(EXPANDED)
        m.open(FileDocuments.uriOf("a.kt")!!)
        assertFalse(m.snapshot()!!.contains("a.kt"))
    }

    @Test fun `a posture change re-applies the layout saved for that posture and keeps the documents`() {
        val m = model(EXPANDED)
        m.open(FileDocuments.uriOf("a.kt")!!)
        m.onWindow(TABLETOP)
        assertTrue(m.now().current.layout.isOpen(Placement.PANEL))
        m.onWindow(BOOK)
        assertTrue(m.now().current.layout.isOpen(Placement.SIDEBAR))
        m.onWindow(EXPANDED)
        assertEquals(listOf("a.kt"), m.now().current.stage.documents.mapNotNull { FileDocuments.pathOf(it) })
        assertTrue(m.opened(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR, Placement.PANEL))
    }

    @Test fun `a damaged snapshot leaves the default layout`() {
        val m = model()
        m.restore("{ not json")
        m.onWindow(MEDIUM)
        assertTrue(m.opened(Placement.SIDEBAR))
    }

    @Test fun `back closes a sheet, then focuses the bar, then leaves the workspace without touching the state`() {
        val m = model(COMPACT)
        m.select(m.item(CoreShell.FILES))
        assertEquals(BackStep.CLOSE_PANEL, m.back(BackContext()))
        assertTrue(m.opened())
        assertEquals(BackStep.FOCUS_NAV, m.back(BackContext()))
        assertTrue(m.now().navFocused)
        val before = m.now()
        assertEquals(BackStep.LEAVE_WORKSPACE, m.back(BackContext()))
        assertEquals(before, m.now())
        assertNotNull(m.now().workspace)
    }

    @Test fun `back steps through a document's history before anything else`() {
        val m = model(EXPANDED)
        m.open(AppDocuments.settingsPage("editor"))
        m.open(AppDocuments.settingsPage("git"))
        assertEquals(BackStep.HISTORY_BACK, m.back(BackContext()))
        assertEquals("easyide://settings/editor", m.now().current.stage.activeGroup.active.toString())
    }

    @Test fun `an open transient wins over everything`() {
        val m = model(COMPACT)
        m.select(m.item(CoreShell.FILES))
        assertEquals(BackStep.CLOSE_TRANSIENT, m.back(BackContext(transientOpen = true)))
        assertTrue(m.opened(Placement.SIDEBAR))
    }

    @Test fun `unsaved work is reported to the caller and the workspace is not left`() {
        val m = model(EXPANDED)
        assertEquals(BackStep.CONFIRM_UNSAVED, m.back(BackContext(hasUnsaved = true)))
        assertNotNull(m.now().workspace)
    }

    @Test fun `what would be saved changes when a restorable document opens`() {
        val m = model(EXPANDED)
        val first = m.snapshot()
        m.open(AppDocuments.settingsPage("git"))
        assertTrue(m.snapshot() != first)
    }
}
