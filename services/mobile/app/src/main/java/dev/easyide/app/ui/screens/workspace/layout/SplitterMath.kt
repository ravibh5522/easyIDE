package dev.easyide.app.ui.screens.workspace.layout

import kotlin.math.abs

/**
 * The geometry of one draggable pane edge, in dp as plain floats so the math has no
 * Compose dependency and can be unit tested. [snaps] are the sizes worth stopping at
 * (a small, a default and a roomy explorer, say); a drag that ends within
 * [SplitterMath.SNAP_THRESHOLD_DP] of one lands exactly on it.
 */
data class PaneLimits(
    val min: Float,
    val max: Float,
    val snaps: List<Float> = emptyList(),
)

/** A pane size after clamping and snapping; [detent] is the bound or snap point it sits on, if any. */
data class Resolved(val size: Float, val detent: Float?)

object SplitterMath {

    /** How close (dp) a drag must come to a snap point or bound before it sticks. */
    const val SNAP_THRESHOLD_DP = 10f

    /** One arrow-key press on a focused splitter. */
    const val KEYBOARD_STEP_DP = 16f

    /**
     * The largest a pane may be: its own [PaneLimits.max], but never so much that the
     * neighbour it shares [available] space with drops below [reserved]. Never below
     * [PaneLimits.min], so a tiny window degrades to the minimum instead of inverting.
     */
    fun ceiling(limits: PaneLimits, available: Float, reserved: Float): Float =
        minOf(limits.max, available - reserved).coerceAtLeast(limits.min)

    /**
     * Turns a raw drag position into a displayed size. Called with the *unsnapped* running
     * value on every drag event, so snapping never eats small movements: the finger keeps
     * accumulating while the pane sticks to a detent until the finger leaves its
     * threshold.
     */
    fun resolve(raw: Float, limits: PaneLimits, ceiling: Float): Resolved {
        val lo = limits.min
        val hi = ceiling.coerceAtLeast(lo)
        val clamped = raw.coerceIn(lo, hi)
        val detents = (limits.snaps.filter { it in lo..hi } + lo + hi)
        val nearest = detents.minByOrNull { abs(it - clamped) }
        return if (nearest != null && abs(nearest - clamped) <= SNAP_THRESHOLD_DP) {
            Resolved(nearest, nearest)
        } else {
            Resolved(clamped, null)
        }
    }

    /**
     * A keyboard nudge of [direction] (+1 grows, -1 shrinks). Steps are larger than the
     * snap threshold, so a snap point never traps the pane: the next step always leaves it.
     */
    fun step(current: Float, direction: Int, limits: PaneLimits, ceiling: Float): Resolved =
        resolve(current + direction * KEYBOARD_STEP_DP, limits, ceiling)
}
