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

/**
 * Heights and widths of repeated chrome, one table per density (density.md 2). Every row, tab, bar
 * and field reads its size here so columns line up across panes. [hitBox] is the layout box of a
 * small visual control (an icon button, a switch); the touch region beyond it comes from
 * [UiMetrics.touchFloor].
 */
@Immutable
data class ControlScale(
    val rowHeight: Dp,
    val sectionHeaderHeight: Dp,
    val toolbarButton: Dp,
    val hitBox: Dp,
    val rowIcon: Dp,
    val railWidth: Dp,
    val railIcon: Dp,
    val bottomBarHeight: Dp,
    val tabHeight: Dp,
    val panelTabHeight: Dp,
    val statusHeight: Dp,
    val fieldHeight: Dp,
    val buttonHeight: Dp,
    val tagHeight: Dp,
    val indent: Dp,
    val hPad: Dp,
    val keyMinWidth: Dp,
    val keyHeight: Dp,
    val panelWidth: Dp,
)

/**
 * Density, corners and scale expanded into the numbers components read (properties.md 2 and 6).
 * Components take these through [LocalMetrics] and never carry a literal that appears here.
 * [touchFloor] is the smallest touch region of an isolated control: 44dp on a phone, 40dp on a
 * window wide enough to expect a keyboard or pointer (density.md 2); no property lowers it.
 */
@Immutable
data class UiMetrics(
    val density: Density,
    val width: WidthClass,
    val corners: Corners,
    val fontScale: Float,
    val space: SpaceScale,
    val radius: RadiusScale,
    val control: ControlScale,
) {
    val touchFloor: Dp get() = touchFloorFor(width)

    /** A corner radius that cannot exceed half the control it rounds, so a "round" setting never inverts a small tag. */
    fun radiusFor(step: Dp, controlHeight: Dp): Dp = minOf(step, controlHeight / 2)

    companion object {
        val TOUCH_FLOOR = 44.dp
        val TOUCH_FLOOR_WIDE = 40.dp

        fun touchFloorFor(width: WidthClass): Dp = if (width.isCompact) TOUCH_FLOOR else TOUCH_FLOOR_WIDE

        val DEFAULT = of(Appearance.DEFAULT)

        /** [width] picks the density when the appearance leaves it on auto, and the touch floor. */
        fun of(appearance: Appearance, width: WidthClass = WidthClass.COMPACT): UiMetrics {
            val d = appearance.density ?: Density.forWidth(width)
            return UiMetrics(
                density = d,
                width = width,
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
            val f = when (d) { Density.DENSE -> 0.85f; Density.COMFORTABLE -> 1f; Density.SPACIOUS -> 1.2f }
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
            Density.DENSE -> ControlScale(
                rowHeight = 28.dp, sectionHeaderHeight = 26.dp, toolbarButton = 28.dp, hitBox = 32.dp, rowIcon = 16.dp,
                railWidth = 52.dp, railIcon = 22.dp, bottomBarHeight = 56.dp, tabHeight = 34.dp, panelTabHeight = 30.dp,
                statusHeight = 24.dp, fieldHeight = 30.dp, buttonHeight = 30.dp, tagHeight = 18.dp, indent = 12.dp, hPad = 10.dp,
                keyMinWidth = 40.dp, keyHeight = 34.dp, panelWidth = 260.dp,
            )
            Density.COMFORTABLE -> ControlScale(
                rowHeight = 36.dp, sectionHeaderHeight = 32.dp, toolbarButton = 32.dp, hitBox = 44.dp, rowIcon = 18.dp,
                railWidth = 56.dp, railIcon = 22.dp, bottomBarHeight = 56.dp, tabHeight = 40.dp, panelTabHeight = 36.dp,
                statusHeight = 28.dp, fieldHeight = 40.dp, buttonHeight = 40.dp, tagHeight = 20.dp, indent = 14.dp, hPad = 14.dp,
                keyMinWidth = 40.dp, keyHeight = 36.dp, panelWidth = 280.dp,
            )
            Density.SPACIOUS -> ControlScale(
                rowHeight = 44.dp, sectionHeaderHeight = 38.dp, toolbarButton = 36.dp, hitBox = 44.dp, rowIcon = 20.dp,
                railWidth = 64.dp, railIcon = 24.dp, bottomBarHeight = 64.dp, tabHeight = 44.dp, panelTabHeight = 40.dp,
                statusHeight = 32.dp, fieldHeight = 44.dp, buttonHeight = 44.dp, tagHeight = 22.dp, indent = 16.dp, hPad = 16.dp,
                keyMinWidth = 44.dp, keyHeight = 40.dp, panelWidth = 300.dp,
            )
        }
    }
}
