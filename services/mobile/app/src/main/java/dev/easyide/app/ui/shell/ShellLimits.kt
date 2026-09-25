package dev.easyide.app.ui.shell

import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement

/**
 * Every threshold the shell engine applies, in one place (repo rule: no hardcoding).
 * The numbers come from shell-model.md, layout-spec.md and extension-ui.md.
 */
object ShellLimits {
    /** Editor groups the stage can hold at its widest (shell-model.md section 8). */
    const val MAX_GROUPS = 4

    /** Back/forward entries kept per group; older ones fall off so history never grows without bound. */
    const val HISTORY_LIMIT = 50

    /** Bottom bar cells before "More" (shell-model.md section 6.2). */
    const val COMPACT_NAV_CAPACITY = 5

    /** A nav title is a label under a 24dp icon on a phone (extension-ui.md section 2.1). */
    const val NAV_TITLE_MAX = 14

    const val NAV_ITEMS_PER_EXTENSION = 3

    const val DOCUMENT_TYPES_PER_EXTENSION = 10

    /** Order values below this are reserved for built-in navigation items. */
    const val EXTENSION_ORDER_FLOOR = 100

    /** How many groups each arrangement can show side by side (layout-spec.md section 1, shell-model.md section 8). */
    fun groupCapacity(arrangement: PaneArrangement): Int = when (arrangement) {
        PaneArrangement.SINGLE_PANE -> 1
        PaneArrangement.ONE_SIDE, PaneArrangement.BOOK, PaneArrangement.TABLETOP -> 2
        PaneArrangement.FULL -> MAX_GROUPS
    }

    /**
     * Placements that open as overlays (sheets) instead of docking: everything on a compact
     * window, and the bottom panel when the window is too short to dock it.
     */
    fun overlays(window: WindowSize): Set<Placement> {
        val arrangement = PaneArrangement.of(window.width, window.fold)
        return when {
            arrangement == PaneArrangement.SINGLE_PANE -> Placement.entries.toSet()
            window.height == HeightClass.COMPACT -> setOf(Placement.PANEL)
            else -> emptySet()
        }
    }
}
