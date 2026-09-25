package dev.easyide.app.ui.shell.host

import dev.easyide.app.ui.screens.workspace.layout.PaneBounds
import dev.easyide.app.ui.screens.workspace.layout.PaneLimits
import dev.easyide.app.ui.screens.workspace.layout.SplitterMath

/** A side panel's width rules in plain dp, so they are testable without a composition. The primary panel's limits are the default; the secondary panel passes its own. */
object PanelSizing {
    val limits: PaneLimits = PaneBounds.explorer

    /** The most the panel may take of [availableDp]: the editor keeps its minimum and the panel stays under its share of the window. */
    fun ceiling(availableDp: Float, limits: PaneLimits = PanelSizing.limits): Float =
        minOf(SplitterMath.ceiling(limits, availableDp, PaneBounds.EDITOR_MIN_WIDTH_DP), availableDp * ShellTokens.PANEL_MAX_FRACTION)
            .coerceAtLeast(limits.min)

    /** The width to draw: the dragged size, else [defaultDp], kept inside the limits (a smaller window shrinks it). */
    fun width(storedDp: Float?, defaultDp: Float, availableDp: Float, limits: PaneLimits = PanelSizing.limits): Float =
        SplitterMath.resolve(storedDp ?: defaultDp, limits, ceiling(availableDp, limits)).size
}
