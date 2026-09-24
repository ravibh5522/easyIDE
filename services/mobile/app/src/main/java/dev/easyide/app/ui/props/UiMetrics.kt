package dev.easyide.app.ui.props

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.WidthClass
import kotlin.math.roundToInt

/** The 4dp grid (with its 2dp half step) after the density factor. [none] is always zero. */
@Immutable
data class SpaceScale(
    val none: Dp, val xxs: Dp, val xs: Dp, val s: Dp, val m: Dp, val l: Dp, val xl: Dp, val xxl: Dp, val xxxl: Dp,
)

/** Corner radii for keys and badges, buttons, cards, sheets. */
@Immutable
data class RadiusScale(val xs: Dp, val s: Dp, val m: Dp, val l: Dp)

/** Heights and widths of repeated chrome. [row] is the tree/change row; [listRow] is the kit row by width class. */
@Immutable
data class ControlScale(
    val row: Dp,
    val tab: Dp,
    val headerAction: Dp,
    val rail: Dp,
    val keyMinWidth: Dp,
    val keyHeight: Dp,
    val panelWidth: Dp,
    private val listRowCompact: Dp,
    private val listRowMedium: Dp,
    private val listRowExpanded: Dp,
) {
    fun listRow(width: WidthClass): Dp = when (width) {
        WidthClass.COMPACT -> listRowCompact
        WidthClass.MEDIUM -> listRowMedium
        WidthClass.EXPANDED -> listRowExpanded
    }
}

/**
 * Density, corners and scale expanded into the numbers components read (properties.md 2 and 6).
 * Components take these through [LocalMetrics] and never carry a literal that appears here.
 * [touchFloor] is a structural constant: no property lowers a hit target below it.
 */
@Immutable
data class UiMetrics(
    val density: Density,
    val corners: Corners,
    val fontScale: Float,
    val space: SpaceScale,
    val radius: RadiusScale,
    val control: ControlScale,
    val touchFloor: Dp = TOUCH_FLOOR,
) {
    /** A corner radius that cannot exceed half the control it rounds, so a "round" setting never inverts a small tag. */
    fun radiusFor(step: Dp, controlHeight: Dp): Dp = minOf(step, controlHeight / 2)

    companion object {
        val TOUCH_FLOOR = 44.dp

        val DEFAULT = of(Appearance.DEFAULT)

        fun of(appearance: Appearance): UiMetrics {
            val d = appearance.density
            return UiMetrics(
                density = d,
                corners = appearance.corners,
                fontScale = appearance.uiScale,
                space = spaceFor(d),
                radius = radiusFor(appearance.corners),
                control = controlFor(d),
            )
        }

        /** Scaled and snapped to the 2dp half step; never below 2dp, so a scaled gap cannot vanish. */
        private fun snap(base: Int, factor: Float): Dp = maxOf((base * factor / SNAP).roundToInt() * SNAP, SNAP).dp

        private const val SNAP = 2

        private fun spaceFor(d: Density): SpaceScale {
            val f = when (d) { Density.COMPACT -> 0.85f; Density.COMFORTABLE -> 1f; Density.SPACIOUS -> 1.2f }
            return SpaceScale(
                none = 0.dp, xxs = snap(2, f), xs = snap(4, f), s = snap(8, f), m = snap(12, f),
                l = snap(16, f), xl = snap(24, f), xxl = snap(32, f), xxxl = snap(48, f),
            )
        }

        private fun radiusFor(c: Corners): RadiusScale = when (c) {
            Corners.SHARP -> RadiusScale(0.dp, 2.dp, 2.dp, 4.dp)
            Corners.SOFT -> RadiusScale(4.dp, 6.dp, 10.dp, 16.dp)
            Corners.ROUND -> RadiusScale(8.dp, 12.dp, 18.dp, 28.dp)
        }

        private fun controlFor(d: Density): ControlScale = when (d) {
            Density.COMPACT -> ControlScale(26.dp, 32.dp, 32.dp, 48.dp, 40.dp, 34.dp, 260.dp, 40.dp, 38.dp, 36.dp)
            Density.COMFORTABLE -> ControlScale(28.dp, 36.dp, 32.dp, 48.dp, 40.dp, 36.dp, 280.dp, 48.dp, 44.dp, 40.dp)
            Density.SPACIOUS -> ControlScale(34.dp, 44.dp, 36.dp, 52.dp, 44.dp, 40.dp, 300.dp, 56.dp, 52.dp, 48.dp)
        }
    }
}
