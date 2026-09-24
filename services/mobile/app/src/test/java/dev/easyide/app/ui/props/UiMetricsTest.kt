package dev.easyide.app.ui.props

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.Radius
import dev.easyide.app.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMetricsTest {

    private val compact = UiMetrics.of(Appearance(density = Density.COMPACT))
    private val comfortable = UiMetrics.DEFAULT
    private val spacious = UiMetrics.of(Appearance(density = Density.SPACIOUS))

    @Test fun `the default appearance reproduces the pre-properties constants exactly`() {
        val s = comfortable.space
        assertEquals(listOf(Spacing.none, Spacing.xxs, Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl, Spacing.xxxl),
            listOf(s.none, s.xxs, s.xs, s.s, s.m, s.l, s.xl, s.xxl, s.xxxl))
        val r = comfortable.radius
        assertEquals(listOf(Radius.xs, Radius.s, Radius.m, Radius.l), listOf(r.xs, r.s, r.m, r.l))
        val c = comfortable.control
        assertEquals(
            listOf(ControlSize.row, ControlSize.tab, ControlSize.headerAction, ControlSize.rail, ControlSize.keyMinWidth, ControlSize.keyHeight),
            listOf(c.row, c.tab, c.headerAction, c.rail, c.keyMinWidth, c.keyHeight),
        )
    }

    @Test fun `density scales spacing monotonically and keeps the half-step grid`() {
        fun steps(metrics: UiMetrics) = metrics.space.run { listOf(xxs, xs, s, m, l, xl, xxl, xxxl) }
        steps(compact).zip(steps(comfortable)).zip(steps(spacious)).forEach { (pair, wide) ->
            assertTrue("$pair $wide", pair.first <= pair.second && pair.second <= wide)
        }
        (steps(compact) + steps(spacious)).forEach { assertEquals("$it is on the 2dp grid", 0f, it.value % 2f, 0f) }
        assertEquals(0.dp, compact.space.none)
        assertTrue("a scaled gap never vanishes", compact.space.xxs >= 2.dp)
    }

    @Test fun `list rows shrink with width class and grow with density`() {
        WidthClass.entries.forEach { w ->
            assertTrue(compact.control.listRow(w) < comfortable.control.listRow(w))
            assertTrue(comfortable.control.listRow(w) < spacious.control.listRow(w))
        }
        assertEquals(48.dp, comfortable.control.listRow(WidthClass.COMPACT))
        assertTrue(comfortable.control.listRow(WidthClass.COMPACT) > comfortable.control.listRow(WidthClass.EXPANDED))
    }

    @Test fun `corner radii grow from sharp to round at every step`() {
        fun radii(c: Corners) = UiMetrics.of(Appearance(corners = c)).radius.run { listOf(xs, s, m, l) }
        radii(Corners.SHARP).zip(radii(Corners.SOFT)).zip(radii(Corners.ROUND)).forEach { (a, round) ->
            assertTrue(a.first <= a.second && a.second <= round)
        }
        assertEquals(0.dp, UiMetrics.of(Appearance(corners = Corners.SHARP)).radius.xs)
    }

    @Test fun `a radius never exceeds half the control it rounds`() {
        val round = UiMetrics.of(Appearance(corners = Corners.ROUND))
        assertEquals(10.dp, round.radiusFor(round.radius.l, 20.dp))
        assertEquals(8.dp, round.radiusFor(round.radius.xs, 20.dp))
    }

    @Test fun `the touch floor is fixed at 44dp for every property combination`() {
        for (d in Density.entries) for (c in Corners.entries) {
            assertEquals(44.dp, UiMetrics.of(Appearance(density = d, corners = c)).touchFloor)
        }
    }

    @Test fun `ui scale is a percent within its range and defaults to 1`() {
        assertEquals(1f, Appearance.DEFAULT.uiScale, 0f)
        assertEquals(1.3f, Appearance(uiScalePercent = Appearance.UI_SCALE_MAX).uiScale, 0.0001f)
        assertEquals(Dp(0f), 0.dp)
    }
}
