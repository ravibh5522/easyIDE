package dev.easyide.app.ui.props

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.theme.Radius
import dev.easyide.app.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMetricsTest {

    private val dense = UiMetrics.of(Appearance(density = Density.DENSE))
    private val comfortable = UiMetrics.DEFAULT
    private val spacious = UiMetrics.of(Appearance(density = Density.SPACIOUS))

    @Test fun `the default appearance is comfortable on a phone and keeps the spacing constants`() {
        assertEquals(Density.COMFORTABLE, comfortable.density)
        val s = comfortable.space
        assertEquals(listOf(Spacing.none, Spacing.xxs, Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl, Spacing.xxxl),
            listOf(s.none, s.xxs, s.xs, s.s, s.m, s.l, s.xl, s.xxl, s.xxxl))
        val r = comfortable.radius
        assertEquals(listOf(Radius.xs, Radius.s, Radius.m, Radius.l), listOf(r.xs, r.s, r.m, r.l))
    }

    @Test fun `auto density is dense on medium and expanded windows and comfortable on compact`() {
        assertEquals(Density.COMFORTABLE, UiMetrics.of(Appearance.DEFAULT, WidthClass.COMPACT).density)
        assertEquals(Density.DENSE, UiMetrics.of(Appearance.DEFAULT, WidthClass.MEDIUM).density)
        assertEquals(Density.DENSE, UiMetrics.of(Appearance.DEFAULT, WidthClass.EXPANDED).density)
    }

    @Test fun `an explicit density wins over the width class`() {
        assertEquals(Density.DENSE, UiMetrics.of(Appearance(density = Density.DENSE), WidthClass.COMPACT).density)
        assertEquals(Density.SPACIOUS, UiMetrics.of(Appearance(density = Density.SPACIOUS), WidthClass.EXPANDED).density)
    }

    @Test fun `the dense and comfortable tables match density md`() {
        val d = dense.control
        assertEquals(listOf(28.dp, 26.dp, 28.dp, 32.dp, 16.dp, 52.dp, 22.dp, 34.dp, 24.dp, 30.dp, 30.dp, 12.dp, 10.dp),
            listOf(d.rowHeight, d.sectionHeaderHeight, d.toolbarButton, d.hitBox, d.rowIcon, d.railWidth, d.railIcon, d.tabHeight, d.statusHeight, d.fieldHeight, d.buttonHeight, d.indent, d.hPad))
        val c = comfortable.control
        assertEquals(listOf(36.dp, 32.dp, 32.dp, 44.dp, 18.dp, 40.dp, 28.dp, 40.dp, 40.dp, 14.dp, 14.dp),
            listOf(c.rowHeight, c.sectionHeaderHeight, c.toolbarButton, c.hitBox, c.rowIcon, c.tabHeight, c.statusHeight, c.fieldHeight, c.buttonHeight, c.indent, c.hPad))
    }

    @Test fun `every control grows or holds from dense to comfortable to spacious`() {
        fun all(m: UiMetrics) = m.control.run {
            listOf(rowHeight, sectionHeaderHeight, toolbarButton, hitBox, rowIcon, railWidth, tabHeight, panelTabHeight, statusHeight, fieldHeight, buttonHeight, tagHeight, indent, hPad)
        }
        all(dense).zip(all(comfortable)).zip(all(spacious)).forEach { (pair, wide) ->
            assertTrue("$pair $wide", pair.first <= pair.second && pair.second <= wide)
        }
    }

    @Test fun `a small control fits inside the row and the toolbar it lives in`() {
        for (m in listOf(dense, comfortable, spacious)) {
            assertTrue(m.control.toolbarButton <= m.control.hitBox)
            assertTrue(m.control.tagHeight < m.control.rowHeight)
            assertTrue(m.control.rowHeight <= m.control.tabHeight + 4.dp)
        }
    }

    @Test fun `density scales spacing monotonically and keeps the half-step grid`() {
        fun steps(metrics: UiMetrics) = metrics.space.run { listOf(xxs, xs, s, m, l, xl, xxl, xxxl) }
        steps(dense).zip(steps(comfortable)).zip(steps(spacious)).forEach { (pair, wide) ->
            assertTrue("$pair $wide", pair.first <= pair.second && pair.second <= wide)
        }
        (steps(dense) + steps(spacious)).forEach { assertEquals("$it is on the 2dp grid", 0f, it.value % 2f, 0f) }
        assertEquals(0.dp, dense.space.none)
        assertTrue("a scaled gap never vanishes", dense.space.xxs >= 2.dp)
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

    @Test fun `the touch floor is 44dp on a phone and 40dp on a wide window for every property combination`() {
        for (d in Density.entries) for (c in Corners.entries) for (w in WidthClass.entries) {
            val floor = UiMetrics.of(Appearance(density = d, corners = c), w).touchFloor
            assertEquals(if (w == WidthClass.COMPACT) 44.dp else 40.dp, floor)
        }
    }

    @Test fun `ui scale is a percent within its range and defaults to 1`() {
        assertEquals(1f, Appearance.DEFAULT.uiScale, 0f)
        assertEquals(1.3f, Appearance(uiScalePercent = Appearance.UI_SCALE_MAX).uiScale, 0.0001f)
        assertEquals(Dp(0f), 0.dp)
    }
}
