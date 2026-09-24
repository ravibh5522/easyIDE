package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.WidthClass

/**
 * The user's dragged pane sizes for one project, in dp. Null means "never dragged":
 * the pane then follows the default for the current window class, so a project the
 * user has not touched still adapts when the window changes.
 */
data class PaneSizes(
    val explorer: Float? = null,
    val bottom: Float? = null,
    val right: Float? = null,
)

/** Which edge a splitter moves. */
enum class Pane { EXPLORER, BOTTOM, RIGHT }

/**
 * Every pane-size number in one place: defaults per window class, bounds and snap
 * points. Layout code never inlines a size.
 */
object PaneBounds {

    /** Room the editor keeps no matter how far a neighbouring pane is dragged. */
    const val EDITOR_MIN_WIDTH_DP = 280f
    const val EDITOR_MIN_HEIGHT_DP = 160f

    val explorer = PaneLimits(min = 160f, max = 480f, snaps = listOf(240f, 280f, 360f))
    val bottom = PaneLimits(min = 96f, max = 720f, snaps = listOf(160f, 260f, 400f))
    val right = PaneLimits(min = 240f, max = 560f, snaps = listOf(280f, 360f, 440f))

    fun limits(pane: Pane): PaneLimits = when (pane) {
        Pane.EXPLORER -> explorer
        Pane.BOTTOM -> bottom
        Pane.RIGHT -> right
    }

    fun defaultExplorer(width: WidthClass): Float = when (width) {
        WidthClass.COMPACT, WidthClass.MEDIUM -> 240f
        WidthClass.EXPANDED -> 280f
    }

    fun defaultBottom(height: HeightClass): Float = if (height.isCompact) 160f else 260f

    fun defaultRight(width: WidthClass): Float = defaultExplorer(width)
}

/** The stored size of [pane], or [default] when the user never dragged it. */
fun PaneSizes.of(pane: Pane, default: Float): Float = when (pane) {
    Pane.EXPLORER -> explorer
    Pane.BOTTOM -> bottom
    Pane.RIGHT -> right
} ?: default

fun PaneSizes.with(pane: Pane, size: Float): PaneSizes = when (pane) {
    Pane.EXPLORER -> copy(explorer = size)
    Pane.BOTTOM -> copy(bottom = size)
    Pane.RIGHT -> copy(right = size)
}
