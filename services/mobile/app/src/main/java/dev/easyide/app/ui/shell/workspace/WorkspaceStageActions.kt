package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import dev.easyide.app.ui.shell.BesideResult
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.GroupTarget
import dev.easyide.app.ui.shell.OpenBeside
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.host.TabAction
import dev.easyide.sandbox.files.FileNode

/**
 * What the workspace does to its stage on the user's behalf: the tab menu, the palette's stage commands
 * and the file menu's "Open to the Side". Files are documents whose buffers the view model owns, so
 * opening one beside opens its buffer too, and closing one goes through [closeDocument] (the unsaved-changes
 * guard), never straight through the shell.
 *
 * "Beside" needs a second group, which only a wide window has: on a phone the document opens in the one
 * group and [notify] says why it did not split, so the request is never silently ignored.
 */
class WorkspaceStageActions(
    private val model: WorkspaceShellModel,
    private val openFile: (FileNode) -> Unit,
    /** Closes a document the way its type needs: a file through the unsaved-changes guard, anything else through the shell. */
    private val closeDocument: (group: Int, uri: DocumentUri) -> Unit,
    private val notify: (String) -> Unit,
    private val oneGroupMessage: String,
) {
    fun openBeside(uri: DocumentUri) {
        val state = model.state.value ?: return
        val splittable = model.documents.resolve(uri).supportsSplit
        model.open(uri, OpenOptions(group = GroupTarget.BESIDE))
        if (OpenBeside.of(state.current.stage, state.groupCapacity, splittable) == BesideResult.ONE_GROUP) notify(oneGroupMessage)
    }

    /** Opens the file's buffer, then puts its document beside: the stage already holds it by the time the buffers sync. */
    fun openFileBeside(node: FileNode) {
        FileDocuments.uriOf(node.relativePath)?.let(::openBeside)
        openFile(node)
    }

    /** The active document, moved to the side (VS Code's `explorer.openToSide` on the editor's own tab). */
    fun openActiveBeside() {
        model.state.value?.current?.stage?.activeGroup?.active?.let(::openBeside)
    }

    fun moveActiveToNextGroup() {
        val stage = model.state.value?.current?.stage ?: return
        if (!OpenBeside.canMoveToNext(stage)) return
        stage.activeGroup.active?.let { model.dispatch(ShellAction.Move(stage.active, it, stage.active + 1)) }
    }

    fun close(group: Int, uri: DocumentUri) = closeDocument(group, uri)

    fun onTabAction(group: Int, uri: DocumentUri, action: TabAction) {
        when (action) {
            TabAction.OPEN_BESIDE -> { model.dispatch(ShellAction.FocusGroup(group)); openBeside(uri) }
            TabAction.MOVE_NEXT -> model.dispatch(ShellAction.Move(group, uri, group + 1))
            TabAction.KEEP -> model.dispatch(ShellAction.Keep(group, uri))
            TabAction.PIN -> model.dispatch(ShellAction.Pin(group, uri))
            TabAction.UNPIN -> model.dispatch(ShellAction.Unpin(group, uri))
            TabAction.CLOSE -> closeDocument(group, uri)
            TabAction.CLOSE_OTHERS -> closeMany(group, uri, CloseScope.OTHERS)
            TabAction.CLOSE_RIGHT -> closeMany(group, uri, CloseScope.TO_THE_RIGHT)
            TabAction.CLOSE_ALL -> closeMany(group, uri, CloseScope.ALL)
        }
    }

    private fun closeMany(group: Int, uri: DocumentUri, scope: CloseScope) {
        val tabs = model.state.value?.current?.stage?.groups?.getOrNull(group) ?: return
        tabs.closing(uri, scope).forEach { closeDocument(group, it) }
    }
}
