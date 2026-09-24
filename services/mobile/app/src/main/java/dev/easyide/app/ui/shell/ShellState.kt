package dev.easyide.app.ui.shell

import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.StageRule
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility

/**
 * Everything one scope (app or workspace) keeps: the selected navigation item, the panel layout for
 * the current arrangement and the layouts of the arrangements it left, the stage, and in app scope
 * the last document opened from each destination (shell-model.md section 12).
 */
data class ScopeState(
    val nav: String?,
    val layout: PanelLayout = PanelLayout(),
    val saved: Map<PaneArrangement, PanelLayout> = emptyMap(),
    val stage: EditorStage = EditorStage(),
    val selection: Map<String, DocumentUri> = emptyMap(),
) {
    companion object {
        /** App scope: Home selected, its project list in the primary panel. */
        fun app(): ScopeState = ScopeState(
            nav = CoreShell.HOME,
            layout = PanelLayout(
                containers = mapOf(Placement.SIDEBAR to CoreShell.HOME_PROJECTS),
                open = StageVisibility(left = true),
            ),
        )

        /** A freshly opened project: Files selected and the `auto` arrangement for [arrangement], one empty group. */
        fun workspace(arrangement: PaneArrangement): ScopeState = ScopeState(
            nav = CoreShell.FILES,
            layout = LayoutPresets.auto(arrangement).applyTo(PanelLayout(), arrangement),
        )
    }
}

/**
 * The whole shell as one immutable value; [ShellReducer] is the only thing that changes it.
 *
 * The scope is derived: it is the workspace's when a project is open, else the app's, so a state
 * cannot claim workspace scope without workspace content. [pushed] is the phone rule for list-first
 * app scope (a document shows full-screen over its list); [navFocused] is back-step 5 (focus has
 * moved to the navigation surface, so the next Back leaves the scope).
 */
data class ShellState(
    val window: WindowSize = WindowSize(WidthClass.COMPACT, HeightClass.REGULAR),
    val app: ScopeState = ScopeState.app(),
    val workspace: ScopeState? = null,
    val pushed: Boolean = false,
    val navFocused: Boolean = false,
) {
    val scope: ShellScope get() = if (workspace == null) ShellScope.APP else ShellScope.WORKSPACE
    val current: ScopeState get() = workspace ?: app
    val arrangement: PaneArrangement get() = PaneArrangement.of(window.width, window.fold)
    val rule: StageRule get() = StageRule.of(arrangement)
    val groupCapacity: Int get() = ShellLimits.groupCapacity(arrangement)

    /** One pane at a time: panels are sheets and app scope is list first. */
    val compact: Boolean get() = arrangement == PaneArrangement.SINGLE_PANE

    fun withCurrent(scopeState: ScopeState): ShellState =
        if (workspace == null) copy(app = scopeState) else copy(workspace = scopeState)
}
