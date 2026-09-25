package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.PaneBounds
import dev.easyide.app.ui.screens.workspace.layout.PaneLimits
import dev.easyide.app.ui.screens.workspace.layout.SplitterMath
import dev.easyide.app.ui.shell.PanelLayout
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellLimits
import kotlin.math.abs

/** How one panel is drawn: not at all, docked beside the stage, or as a sheet over it. */
enum class PanelMode { HIDDEN, DOCKED, SHEET }

/** What the workspace body draws for a window and a panel layout: one mode per placement. */
data class LayoutPlan(val arrangement: PaneArrangement, val sidebar: PanelMode, val secondary: PanelMode, val bottom: PanelMode) {
    fun mode(placement: Placement): PanelMode = when (placement) {
        Placement.SIDEBAR -> sidebar
        Placement.SECONDARY_SIDEBAR -> secondary
        Placement.PANEL -> bottom
    }
}

/**
 * The layout choice per size class and posture (layout-spec.md sections 1 and 4), pure so it is
 * testable without a composition. The engine already decides which panels may be open at once
 * ([PanelLayout] and its stage rule); this decides how an open one is drawn: a phone shows every
 * panel as a sheet, a short window only its bottom panel, everything else docks.
 */
object WorkspaceLayout {
    fun plan(window: WindowSize, layout: PanelLayout): LayoutPlan {
        val sheets = ShellLimits.overlays(window)
        fun mode(p: Placement) = when {
            !layout.isOpen(p) -> PanelMode.HIDDEN
            p in sheets -> PanelMode.SHEET
            else -> PanelMode.DOCKED
        }
        return LayoutPlan(PaneArrangement.of(window.width, window.fold), mode(Placement.SIDEBAR), mode(Placement.SECONDARY_SIDEBAR), mode(Placement.PANEL))
    }
}

/**
 * The bottom panel's height rules. As a sheet (a phone) it rests on one of three detents, kept as a
 * fraction of the height it may use so the keyboard shrinking the window does not throw it to a
 * different detent, and it never gets shorter than [MIN_USABLE_DP], the least at which a terminal
 * shows its tab bar and a few rows. Docked, it is dp between a quarter and 70% of that height.
 * The stored size is a fraction in one mode and dp in the other; a value of the wrong kind is
 * ignored, so a layout saved before a window changed class degrades to the default.
 */
object BottomSizing {
    const val PEEK = 0.25f
    const val HALF = 0.5f
    const val FULL = 1f
    const val DOCKED_DEFAULT = 0.35f
    private const val DOCKED_MIN = 0.25f
    private const val DOCKED_MAX = 0.7f
    const val MIN_USABLE_DP = 160f

    private val FRACTIONS = listOf(PEEK, HALF, FULL)

    /** A stored size at or below 1 is a fraction; a dp size is always well above 1. */
    private fun fractionOf(stored: Float?): Float? = stored?.takeIf { it in 0f..1f }

    private fun dpOf(stored: Float?): Float? = stored?.takeIf { it > 1f }

    /** The detent nearest to [stored] as a fraction (half when nothing usable is stored). */
    fun sheetFraction(stored: Float?): Float {
        val f = fractionOf(stored) ?: return HALF
        return FRACTIONS.minBy { abs(it - f) }
    }

    /** The sheet's height in dp: its detent of [availableDp], never below the usable minimum and never above the window. */
    fun sheetHeight(stored: Float?, availableDp: Float): Float =
        (sheetFraction(stored) * availableDp).coerceAtLeast(MIN_USABLE_DP).coerceAtMost(availableDp)

    /** The fraction the sheet moves to on a tap of its handle: peek, half, full, then round again. */
    fun nextFraction(stored: Float?): Float = FRACTIONS[(FRACTIONS.indexOf(sheetFraction(stored)) + 1) % FRACTIONS.size]

    /** The detent fraction a sheet of [heightDp] (mid-drag) settles on. */
    fun settle(heightDp: Float, availableDp: Float): Float = FRACTIONS.minBy { abs(it - heightDp / availableDp.coerceAtLeast(1f)) }

    fun dockedLimits(availableDp: Float): PaneLimits = PaneLimits(
        min = minOf(maxOf(DOCKED_MIN * availableDp, MIN_USABLE_DP), DOCKED_MAX * availableDp),
        max = DOCKED_MAX * availableDp,
    )

    /** The docked height in dp: the dragged size, else 35%, kept inside the limits. */
    fun dockedHeight(stored: Float?, availableDp: Float): Float {
        val limits = dockedLimits(availableDp)
        return SplitterMath.resolve(dpOf(stored) ?: (DOCKED_DEFAULT * availableDp), limits, SplitterMath.ceiling(limits, availableDp, PaneBounds.EDITOR_MIN_HEIGHT_DP)).size
    }

    /** The most a docked panel may grow to while the stage above keeps its minimum. */
    fun dockedCeiling(availableDp: Float): Float =
        SplitterMath.ceiling(dockedLimits(availableDp), availableDp, PaneBounds.EDITOR_MIN_HEIGHT_DP)
}
