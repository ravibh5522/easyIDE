package dev.easyide.app.ui.shell

import dev.easyide.app.ui.foundation.FoldPosture
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.HingeAxis
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize

internal val COMPACT = WindowSize(WidthClass.COMPACT, HeightClass.REGULAR)
internal val PHONE_LANDSCAPE = WindowSize(WidthClass.MEDIUM, HeightClass.COMPACT)
internal val MEDIUM = WindowSize(WidthClass.MEDIUM, HeightClass.REGULAR)
internal val EXPANDED = WindowSize(WidthClass.EXPANDED, HeightClass.REGULAR)
internal val BOOK = EXPANDED.copy(fold = FoldPosture(HingeAxis.VERTICAL, true, false, false, 900, 0, 910, 1000))
internal val TABLETOP = EXPANDED.copy(fold = FoldPosture(HingeAxis.HORIZONTAL, true, false, false, 0, 500, 1000, 510))

/** A shell in [window] with a project open and Files selected. */
internal fun workspaceShell(window: WindowSize): ShellState {
    val base = ShellState(window = window)
    return base.copy(workspace = ScopeState.workspace(base.arrangement))
}

internal fun ShellState.act(vararg actions: ShellAction): ShellState =
    actions.fold(this) { s, a -> ShellReducer.reduce(s, a, ENV) }

internal fun open(uri: DocumentUri, options: OpenOptions = OpenOptions()) = ShellAction.Open(uri, options)

internal val FILES_NAV = ShellAction.SelectNav(CoreShell.FILES, ContainerRef(Placement.SIDEBAR, CoreShell.EXPLORER))
internal val SEARCH_NAV = ShellAction.SelectNav(CoreShell.SEARCH, ContainerRef(Placement.SIDEBAR, CoreShell.SEARCH_PANEL))
internal val TERMINAL_NAV = ShellAction.SelectNav(CoreShell.TERMINAL_NAV, ContainerRef(Placement.PANEL, CoreShell.TERMINAL))
internal val SETTINGS_NAV = ShellAction.SelectNav(CoreShell.SETTINGS, ContainerRef(Placement.SIDEBAR, CoreShell.SETTINGS_CATEGORIES))
internal val HOME_NAV = ShellAction.SelectNav(CoreShell.HOME, ContainerRef(Placement.SIDEBAR, CoreShell.HOME_PROJECTS))

internal fun ShellState.openPanels(): Set<Placement> = Placement.entries.filter { current.layout.isOpen(it) }.toSet()

internal fun ShellState.tabNames(): List<List<String>> = current.stage.groups.map { g -> g.tabs.map { it.uri.name } }
