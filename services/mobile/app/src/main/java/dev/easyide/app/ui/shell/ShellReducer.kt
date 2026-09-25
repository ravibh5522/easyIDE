package dev.easyide.app.ui.shell

import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.with

/** A container of a navigation item's target, resolved by the host: which placement it shows in. */
data class ContainerRef(val placement: Placement, val id: String)

sealed interface ShellAction {
    data class Resize(val window: WindowSize) : ShellAction

    /** A navigation item with a container target was tapped. Command targets never reach the reducer. */
    data class SelectNav(val navId: String, val container: ContainerRef) : ShellAction
    data class TogglePanel(val placement: Placement) : ShellAction

    /** Opens [placement] showing [container] (or what it already shows), marking [nav] selected; unlike [SelectNav] it never collapses an open panel. */
    data class ShowPanel(val placement: Placement, val container: String? = null, val nav: String? = null) : ShellAction

    /** A dragged pane edge; a null [size] forgets the drag so the pane follows its default again. */
    data class ResizePane(val pane: Pane, val size: Float?) : ShellAction

    data class Open(val uri: DocumentUri, val options: OpenOptions = OpenOptions()) : ShellAction
    data class Close(val group: Int, val key: DocumentUri) : ShellAction
    data class CloseMany(val group: Int, val key: DocumentUri, val scope: CloseScope) : ShellAction
    data class CloseWhere(val matches: (DocumentUri) -> Boolean) : ShellAction
    data class Pin(val group: Int, val key: DocumentUri) : ShellAction
    data class Unpin(val group: Int, val key: DocumentUri) : ShellAction
    data class Keep(val group: Int, val key: DocumentUri) : ShellAction
    data class Reorder(val group: Int, val key: DocumentUri, val to: Int) : ShellAction
    data class Activate(val group: Int, val key: DocumentUri) : ShellAction
    data class FocusGroup(val group: Int) : ShellAction
    data class Move(val from: Int, val key: DocumentUri, val to: Int) : ShellAction
    data object Split : ShellAction
    data class Unsplit(val group: Int) : ShellAction

    data class ApplyPreset(val id: String) : ShellAction
    data class OpenWorkspace(val state: ScopeState) : ShellAction
    data object LeaveWorkspace : ShellAction
}

/** What the reducer needs from the host: how a URI resolves to a type, and the extension presets on offer. */
class ShellEnv(val typeOf: (DocumentUri) -> DocumentType, val presets: List<LayoutPreset> = emptyList())

object ShellReducer {

    fun reduce(state: ShellState, action: ShellAction, env: ShellEnv): ShellState {
        val next = when (action) {
            is ShellAction.Resize -> resize(state, action.window, env)
            is ShellAction.SelectNav -> selectNav(state, action, env)
            is ShellAction.TogglePanel -> onLayout(state) { it.toggle(action.placement, state.rule) }
            is ShellAction.ShowPanel -> state.withCurrent(state.current.let { it.copy(nav = action.nav ?: it.nav, layout = it.layout.show(action.placement, state.rule, action.container)) })
            is ShellAction.ResizePane -> onLayout(state) { it.copy(sizes = it.sizes.with(action.pane, action.size)) }
            is ShellAction.Open -> open(state, action.uri, action.options, env)
            is ShellAction.Close -> onStage(state) { it.close(action.group, action.key) }
            is ShellAction.CloseMany -> onStage(state) { it.close(action.group, action.key, action.scope) }
            is ShellAction.CloseWhere -> onStage(state) { it.closeWhere(action.matches) }
            is ShellAction.Pin -> onStage(state) { it.pin(action.group, action.key) }
            is ShellAction.Unpin -> onStage(state) { it.unpin(action.group, action.key) }
            is ShellAction.Keep -> onStage(state) { it.keep(action.group, action.key) }
            is ShellAction.Reorder -> onStage(state) { it.reorder(action.group, action.key, action.to) }
            is ShellAction.Activate -> onStage(state) { it.activate(action.group, action.key) }
            is ShellAction.FocusGroup -> onStage(state) { it.focusGroup(action.group) }
            is ShellAction.Move -> onStage(state) { it.move(action.from, action.key, action.to) }
            is ShellAction.Split -> onStage(state) { it.split(state.groupCapacity) }
            is ShellAction.Unsplit -> onStage(state) { it.unsplit(action.group) }
            is ShellAction.ApplyPreset -> applyPreset(state, action.id, env)
            is ShellAction.OpenWorkspace -> state.copy(workspace = action.state, pushed = false)
            is ShellAction.LeaveWorkspace -> state.copy(workspace = null, pushed = false)
        }
        // Any real interaction ends "focus is on the navigation surface"; a resize is not one.
        return if (action is ShellAction.Resize) next else next.copy(navFocused = false)
    }

    private fun onStage(state: ShellState, f: (EditorStage) -> EditorStage): ShellState =
        state.withCurrent(state.current.let { it.copy(stage = f(it.stage)) })

    private fun onLayout(state: ShellState, f: (PanelLayout) -> PanelLayout): ShellState =
        state.withCurrent(state.current.let { it.copy(layout = f(it.layout)) })

    /**
     * A size class or posture change re-applies the layout saved for the new arrangement, else the
     * scope's preset (`auto` when the chosen one is not offered there). Documents are never lost:
     * surplus groups merge into their neighbour. A different group split is not restored, since the
     * merged documents cannot be told apart from the ones that were always together.
     */
    private fun resize(state: ShellState, window: WindowSize, env: ShellEnv): ShellState {
        val from = state.arrangement
        val moved = state.copy(window = window)
        val to = moved.arrangement
        if (from == to) return moved.mapScopes { it.copy(layout = it.layout.constrain(moved.rule)) }
        return moved.mapScopes { scope, isWorkspace ->
            val saved = scope.saved + (from to scope.layout)
            val layout = saved[to] ?: fresh(scope.layout, to, isWorkspace, env)
            scope.copy(layout = layout.constrain(moved.rule), saved = saved - to, stage = scope.stage.fit(moved.groupCapacity))
        }
    }

    private fun fresh(layout: PanelLayout, to: PaneArrangement, isWorkspace: Boolean, env: ShellEnv): PanelLayout {
        if (!isWorkspace) return layout
        val preset = LayoutPresets.find(layout.preset, to, env.presets) ?: LayoutPresets.auto(to)
        return preset.applyTo(layout, to).copy(preset = layout.preset)
    }

    private fun ShellState.mapScopes(f: (ScopeState) -> ScopeState): ShellState = mapScopes { s, _ -> f(s) }

    private fun ShellState.mapScopes(f: (ScopeState, Boolean) -> ScopeState): ShellState =
        copy(app = f(app, false), workspace = workspace?.let { f(it, true) })

    /**
     * Tapping the item whose container is already showing collapses its panel (shell-model.md
     * section 7); otherwise the container shows there. In app scope the destination's last document
     * comes back onto the stage, and on a phone that lands on the list, not on the document.
     */
    private fun selectNav(state: ShellState, a: ShellAction.SelectNav, env: ShellEnv): ShellState {
        val scope = state.current
        val p = a.container.placement
        val collapsing = scope.nav == a.navId && scope.layout.container(p) == a.container.id && scope.layout.isOpen(p)
        val layout = if (collapsing) scope.layout.hide(p, state.rule) else scope.layout.show(p, state.rule, a.container.id)
        val selected = state.withCurrent(scope.copy(nav = a.navId, layout = layout)).copy(pushed = false)
        val remembered = scope.selection[a.navId]
        if (state.scope != ShellScope.APP || remembered == null || collapsing) return selected
        return open(selected, remembered, OpenOptions(preview = true), env).copy(pushed = false)
    }

    private fun open(state: ShellState, uri: DocumentUri, options: OpenOptions, env: ShellEnv): ShellState {
        val scope = state.current
        val listShowing = state.scope == ShellScope.APP && state.compact && !state.pushed
        val opened = scope.stage.open(uri, options, state.groupCapacity, env.typeOf(uri))
        // The trail starts at the document just opened, so Back from it goes to the list, not to an older page.
        val stage = if (listShowing) opened.clearHistory() else opened
        val inApp = state.scope == ShellScope.APP
        val layout = if (options.focus && !inApp) {
            scope.layout.dismiss(ShellLimits.overlays(state.window), state.rule)
        } else {
            scope.layout
        }
        val selection = if (options.focus && inApp && scope.nav != null) scope.selection + (scope.nav to uri) else scope.selection
        return state.withCurrent(scope.copy(layout = layout, stage = stage, selection = selection))
            .copy(pushed = state.pushed || (inApp && options.focus))
    }

    private fun applyPreset(state: ShellState, id: String, env: ShellEnv): ShellState {
        val workspace = state.workspace ?: return state
        val preset = LayoutPresets.find(id, state.arrangement, env.presets) ?: return state
        val layout = preset.applyTo(workspace.layout, state.arrangement).copy(preset = id)
        val stage = workspace.stage.arranged(preset.groups, preset.axis, state.groupCapacity)
        return state.copy(workspace = workspace.copy(layout = layout, stage = stage))
    }
}
