package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.COMPACT
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.MEDIUM
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.app.ui.shell.file
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.host.TabAction
import dev.easyide.sandbox.files.FileNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStageActionsTest {
    private val registries = AppDocuments.registries { "t" }.forWorkspace()
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val diff = requireNotNull(Comparison.unstaged("a.kt").uri)

    private class Log {
        val toasts = ArrayList<String>()
        val opened = ArrayList<String>()
        val closed = ArrayList<Pair<Int, DocumentUri>>()
    }

    private fun setup(window: dev.easyide.app.ui.foundation.WindowSize): Triple<WorkspaceShellModel, WorkspaceStageActions, Log> {
        val model = WorkspaceShellModel(registries.documents, registries.containers).also { it.onWindow(window) }
        val log = Log()
        val actions = WorkspaceStageActions(
            model, { log.opened += it.relativePath }, { g, u -> log.closed += g to u; model.dispatch(ShellAction.Close(g, u)) },
            { log.toasts += it }, "one group",
        )
        return Triple(model, actions, log)
    }

    private val WorkspaceShellModel.stage get() = state.value!!.current.stage

    @Test fun `a phone opens the document in place and says why`() {
        val (model, actions, log) = setup(COMPACT)
        model.open(a)
        actions.openBeside(diff)
        assertEquals(1, model.stage.groups.size)
        assertEquals(listOf(a, diff), model.stage.documents)
        assertEquals(listOf("one group"), log.toasts)
    }

    @Test fun `a wide window splits without a message`() {
        val (model, actions, log) = setup(EXPANDED)
        model.open(a)
        actions.openBeside(diff)
        assertEquals(2, model.stage.groups.size)
        assertEquals(diff, model.stage.groups[1].active)
        assertTrue(log.toasts.isEmpty())
    }

    @Test fun `a file opened beside opens its buffer and lands in the second group`() {
        val (model, actions, log) = setup(MEDIUM)
        model.open(a)
        actions.openFileBeside(FileNode("b.kt", "b.kt", isDirectory = false, sizeBytes = 0))
        assertEquals(listOf("b.kt"), log.opened)
        assertEquals(b, model.stage.groups[1].active)
        assertEquals(1, model.stage.active)
    }

    @Test fun `the palette command moves the active document beside and then to the next group`() {
        val (model, actions, _) = setup(EXPANDED)
        model.open(a)
        model.open(b)
        actions.openActiveBeside()
        assertEquals(listOf(listOf(a), listOf(b)), model.stage.groups.map { g -> g.tabs.map { it.uri } })
        model.dispatch(ShellAction.FocusGroup(0))
        actions.moveActiveToNextGroup()
        // The active group held only a: moving it leaves that group empty, so it is dropped and b's group is the one left.
        assertEquals(listOf(listOf(b, a)), model.stage.groups.map { g -> g.tabs.map { it.uri } })
        assertEquals(a, model.stage.activeGroup.active)
    }

    @Test fun `tab actions change the tab and close through the caller`() {
        val (model, actions, log) = setup(EXPANDED)
        model.open(a, dev.easyide.app.ui.shell.OpenOptions(preview = true))
        model.open(b)
        actions.onTabAction(0, b, TabAction.PIN)
        assertEquals(b, model.stage.groups[0].tabs.first().uri)
        actions.onTabAction(0, b, TabAction.UNPIN)
        actions.onTabAction(0, a, TabAction.KEEP)
        actions.onTabAction(0, a, TabAction.CLOSE_OTHERS)
        assertEquals(listOf(0 to b), log.closed)
        assertEquals(listOf(a), model.stage.documents)
    }

    @Test fun `close all goes through the caller for every tab, files included`() {
        val (model, actions, log) = setup(MEDIUM)
        model.open(a); model.open(b); model.open(diff)
        actions.onTabAction(0, a, TabAction.CLOSE_ALL)
        assertEquals(listOf(a, b, diff), log.closed.map { it.second })
    }
}
