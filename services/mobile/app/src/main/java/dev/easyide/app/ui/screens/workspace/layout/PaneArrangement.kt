package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.app.ui.foundation.FoldPosture
import dev.easyide.app.ui.foundation.WidthClass

/**
 * How the workspace lays its regions out (docs/ux-overhaul/arch.md, "Adaptive mapping").
 * Chosen from the window's width class and the device's fold, never from device type or
 * orientation: a tablet in split-screen is legitimately [SINGLE_PANE].
 */
enum class PaneArrangement {
    /** Compact: one full-screen pane at a time, a bottom switcher, explorer/Git in a modal drawer. */
    SINGLE_PANE,

    /** Medium: side panels dock, but only one at a time. */
    ONE_SIDE,

    /** Expanded: explorer, editor, terminal and the right panel may all show. */
    FULL,

    /** Book posture: the explorer fills the page on one side of the hinge, editor and terminal the other. */
    BOOK,

    /** Tabletop posture: editor above the hinge, terminal below it. */
    TABLETOP;

    companion object {
        /** A fold only matters when it is in a posture that splits the window; a flat one is ignored. */
        fun of(width: WidthClass, fold: FoldPosture?): PaneArrangement = when {
            fold?.isBook == true -> BOOK
            fold?.isTabletop == true -> TABLETOP
            else -> when (width) {
                WidthClass.COMPACT -> SINGLE_PANE
                WidthClass.MEDIUM -> ONE_SIDE
                WidthClass.EXPANDED -> FULL
            }
        }
    }
}

/** A container cut at a hinge: [first] and [second] are the two page sizes, [gap] the hidden strip between. */
data class HingeSplit(val first: Int, val gap: Int, val second: Int) {

    companion object {
        /**
         * Where the hinge falls inside a container that starts at [origin] (window px) and is
         * [size] long, or null when it does not cross it - the caller then lays out as if flat.
         * An occluding hinge takes its full width as a gap; a crease that hides nothing splits
         * at its centre with no gap.
         */
        fun around(spanStart: Int, spanEnd: Int, occludes: Boolean, origin: Int, size: Int): HingeSplit? {
            val gap = if (occludes) (spanEnd - spanStart).coerceAtLeast(0) else 0
            val first = (if (occludes) spanStart else (spanStart + spanEnd) / 2) - origin
            if (first <= 0 || first + gap >= size) return null
            return HingeSplit(first, gap, size - first - gap)
        }
    }
}
