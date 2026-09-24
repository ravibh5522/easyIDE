package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.StageRule
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility

/**
 * A named arrangement (shell-model.md section 3): which containers show where, which panels are
 * open, and how the stage splits. [arrangements] says where it is offered; empty means everywhere.
 * Extension presets (`layoutPresets`) are the same type.
 */
data class LayoutPreset(
    val id: String,
    val title: String,
    val containers: Map<Placement, String>,
    val open: Set<Placement>,
    val groups: Int = 1,
    val axis: SplitAxis = SplitAxis.ROW,
    val arrangements: Set<PaneArrangement> = emptySet(),
) {
    fun appliesTo(arrangement: PaneArrangement): Boolean = arrangements.isEmpty() || arrangement in arrangements

    /**
     * [layout] rearranged: this preset's containers replace the current ones, only its panels are
     * open, and the window's rule for [arrangement] still holds. The caller records which preset id
     * (this one, or `auto`) the user chose.
     */
    fun applyTo(layout: PanelLayout, arrangement: PaneArrangement): PanelLayout {
        val closed = layout.copy(containers = layout.containers + containers, open = StageVisibility())
        return open.fold(closed) { l, p -> l.show(p, StageRule.FREE) }.constrain(StageRule.of(arrangement))
    }
}

object LayoutPresets {
    const val AUTO = "auto"

    private val TWO_PANE = setOf(PaneArrangement.ONE_SIDE, PaneArrangement.FULL)

    val FOCUS = LayoutPreset("focus", "Focus", emptyMap(), open = emptySet())

    val CLASSIC = LayoutPreset(
        "classic", "Classic",
        mapOf(Placement.SIDEBAR to CoreShell.EXPLORER, Placement.PANEL to CoreShell.TERMINAL),
        open = setOf(Placement.SIDEBAR),
    )

    val WORKBENCH = LayoutPreset(
        "workbench", "Workbench",
        mapOf(
            Placement.SIDEBAR to CoreShell.EXPLORER,
            Placement.SECONDARY_SIDEBAR to CoreShell.OUTLINE,
            Placement.PANEL to CoreShell.TERMINAL,
        ),
        open = setOf(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR, Placement.PANEL),
        groups = 2,
        arrangements = TWO_PANE,
    )

    val TERMINAL_FIRST = LayoutPreset(
        "terminalFirst", "Terminal first",
        mapOf(Placement.SIDEBAR to CoreShell.EXPLORER, Placement.PANEL to CoreShell.TERMINAL),
        open = setOf(Placement.PANEL),
    )

    /** Book: panels stacked on the left page, the stage on the right (layout-spec.md section 4.5). */
    val BOOK = LayoutPreset(
        "book", "Book",
        mapOf(Placement.SIDEBAR to CoreShell.EXPLORER, Placement.PANEL to CoreShell.TERMINAL),
        open = setOf(Placement.SIDEBAR, Placement.PANEL),
        arrangements = setOf(PaneArrangement.BOOK),
    )

    val TABLETOP = LayoutPreset(
        "tabletop", "Tabletop",
        mapOf(Placement.SIDEBAR to CoreShell.EXPLORER, Placement.PANEL to CoreShell.TERMINAL),
        open = setOf(Placement.PANEL),
        arrangements = setOf(PaneArrangement.TABLETOP),
    )

    val BUILT_IN = listOf(FOCUS, CLASSIC, WORKBENCH, TERMINAL_FIRST, BOOK, TABLETOP)

    /**
     * What `auto` means for an arrangement. A landscape tablet is FULL yet only about 1150dp wide:
     * three docked panels would leave the editor barely 600dp, so `auto` stays on two and the
     * workbench is an explicit choice.
     */
    fun auto(arrangement: PaneArrangement): LayoutPreset = when (arrangement) {
        PaneArrangement.SINGLE_PANE -> FOCUS
        PaneArrangement.ONE_SIDE -> CLASSIC
        PaneArrangement.FULL -> CLASSIC
        PaneArrangement.BOOK -> BOOK
        PaneArrangement.TABLETOP -> TABLETOP
    }

    /** The presets offered for [arrangement]: built-in first, then [extra] (a pack's), each only where it applies. */
    fun offered(arrangement: PaneArrangement, extra: List<LayoutPreset> = emptyList()): List<LayoutPreset> =
        (BUILT_IN + extra).filter { it.appliesTo(arrangement) }.distinctBy { it.id }

    /** The preset [id] names for [arrangement], `auto` resolved; null when unknown or not offered there. */
    fun find(id: String, arrangement: PaneArrangement, extra: List<LayoutPreset> = emptyList()): LayoutPreset? =
        if (id == AUTO) auto(arrangement) else offered(arrangement, extra).firstOrNull { it.id == id }
}
