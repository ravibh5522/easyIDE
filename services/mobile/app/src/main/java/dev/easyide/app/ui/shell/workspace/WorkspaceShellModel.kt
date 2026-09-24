package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.shell.BackContext
import dev.easyide.app.ui.shell.BackNavigation
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.ContainerRef
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellEnv
import dev.easyide.app.ui.shell.ShellReducer
import dev.easyide.app.ui.shell.ShellSnapshot
import dev.easyide.app.ui.shell.ShellState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The workspace-scope shell of one project: a [ShellState] whose workspace is open, changed only
 * through [ShellReducer]. It belongs to the workspace session, not to the screen, so leaving the
 * screen parks it and coming back finds the panels and documents where they were (decision 0023).
 *
 * [state] is null until the first window arrives: what a fresh project shows (and which saved
 * layout applies) depends on the window's arrangement, which only the composition knows. A saved
 * snapshot that arrives before the window waits for it; one that arrives after replaces the
 * defaults in place, keeping the documents the screen has opened meanwhile.
 */
class WorkspaceShellModel(
    val documents: DocumentRegistry,
    val containers: ContainerRegistry,
    private val env: ShellEnv = ShellEnv(documents::resolve),
) {
    private val mutable = MutableStateFlow<ShellState?>(null)
    val state: StateFlow<ShellState?> = mutable.asStateFlow()
    private var pending: String? = null

    /** Each new snapshot text, for the session to write; emits once per change of what would be saved. */
    val snapshots = mutable.filterNotNull().map { snapshotOf(it) }.distinctUntilChanged()

    /** What to save now: the state's snapshot, or the saved one still waiting for a window, so an early save never erases it. */
    fun snapshot(): String? = mutable.value?.let(::snapshotOf) ?: pending

    private fun snapshotOf(s: ShellState): String =
        ShellSnapshot.encodeWorkspace(s.current, s.arrangement, { documents.resolve(it) })

    /** Called with the window on every change: the first call builds the state, later ones re-fit it. */
    fun onWindow(window: WindowSize) {
        val current = mutable.value
        when {
            current == null -> mutable.value = fresh(window)
            current.window != window -> dispatch(ShellAction.Resize(window))
        }
    }

    private fun fresh(window: WindowSize): ShellState {
        val base = ShellState(window = window)
        val restored = pending?.let { ShellSnapshot.decodeWorkspace(it, base.arrangement) }?.state
        pending = null
        return base.copy(workspace = restored ?: ScopeState.workspace(base.arrangement))
    }

    /** Applies a saved snapshot: now when the window is known, else when it arrives. */
    fun restore(json: String) {
        val current = mutable.value ?: run { pending = json; return }
        val restored = ShellSnapshot.decodeWorkspace(json, current.arrangement)?.state ?: return
        // What the screen opened before the restore landed stays: a saved stage never has the files, and they are what the user asked for.
        mutable.value = current.copy(workspace = restored.copy(stage = current.current.stage.takeIf { it.documents.isNotEmpty() } ?: restored.stage))
    }

    fun dispatch(action: ShellAction) {
        mutable.update { s -> s?.let { ShellReducer.reduce(it, action, env) } }
    }

    fun open(uri: DocumentUri, options: OpenOptions = OpenOptions()) = dispatch(ShellAction.Open(uri, options))

    /** Shows the container [item] leads to; tapping the one already showing collapses its panel. */
    fun select(item: NavItem) {
        val target = item.target as? NavTarget.Container ?: return
        val spec = containers.byId(target.id) ?: return
        dispatch(ShellAction.SelectNav(item.id, ContainerRef(containers.placementOf(spec), spec.id)))
    }

    /** Opens the panel that holds [containerId] and shows it, whatever was showing there. */
    fun reveal(containerId: String) {
        val spec = containers.byId(containerId) ?: return
        dispatch(ShellAction.ShowPanel(containers.placementOf(spec), containerId, CoreShell.navIdOf(containerId)))
    }

    /** The command form of a navigation tap: shows [containerId], or collapses its panel when it is already the one showing. */
    fun toggleContainer(containerId: String) {
        val layout = mutable.value?.current?.layout ?: return
        val spec = containers.byId(containerId) ?: return
        val placement = containers.placementOf(spec)
        val showing = layout.isOpen(placement) && containers.active(placement, layout.container(placement), ShellScope.WORKSPACE)?.id == containerId
        if (showing) toggle(placement) else reveal(containerId)
    }

    fun toggle(placement: Placement) = dispatch(ShellAction.TogglePanel(placement))

    /** Closes the document the active group shows (a page that finished, such as an uninstalled extension's). */
    fun closeActive() {
        val stage = mutable.value?.current?.stage ?: return
        stage.activeGroup.active?.let { dispatch(ShellAction.Close(stage.active, it)) }
    }

    fun resizePane(pane: Pane, size: Float?) = dispatch(ShellAction.ResizePane(pane, size))

    /**
     * One Back press through the rules of shell-model.md section 11. Leaving the workspace is the
     * caller's to do (it pops the route), so that step leaves the state as it was.
     */
    fun back(context: BackContext): BackStep {
        val current = mutable.value ?: return BackStep.SYSTEM
        val result = BackNavigation.back(current, context)
        if (result.step != BackStep.LEAVE_WORKSPACE && result.step != BackStep.CONFIRM_UNSAVED) mutable.value = result.state
        return result.step
    }
}
