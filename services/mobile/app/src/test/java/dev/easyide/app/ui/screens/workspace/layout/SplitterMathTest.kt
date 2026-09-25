package dev.easyide.app.ui.screens.workspace.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitterMathTest {

    private val limits = PaneLimits(min = 100f, max = 400f, snaps = listOf(200f, 300f))

    @Test
    fun `raw values clamp to the bounds`() {
        assertEquals(100f, SplitterMath.resolve(-50f, limits, ceiling = 400f).size, 0f)
        assertEquals(400f, SplitterMath.resolve(900f, limits, ceiling = 400f).size, 0f)
    }

    @Test
    fun `a value near a snap point sticks to it and reports the detent`() {
        val near = SplitterMath.resolve(205f, limits, 400f)
        assertEquals(200f, near.size, 0f)
        assertEquals(200f, near.detent)
    }

    @Test
    fun `a value away from every detent is left alone`() {
        val free = SplitterMath.resolve(250f, limits, 400f)
        assertEquals(250f, free.size, 0f)
        assertNull(free.detent)
    }

    @Test
    fun `bounds are detents too`() {
        assertEquals(100f, SplitterMath.resolve(108f, limits, 400f).detent)
        assertEquals(400f, SplitterMath.resolve(395f, limits, 400f).detent)
    }

    @Test
    fun `a ceiling below the max removes snap points above it`() {
        val r = SplitterMath.resolve(300f, limits, ceiling = 250f)
        assertEquals(250f, r.size, 0f)
        assertEquals(250f, r.detent)
    }

    @Test
    fun `ceiling never drops below the minimum in a tiny window`() {
        assertEquals(100f, SplitterMath.ceiling(limits, available = 150f, reserved = 280f), 0f)
        assertEquals(400f, SplitterMath.ceiling(limits, available = 2000f, reserved = 280f), 0f)
        assertEquals(320f, SplitterMath.ceiling(limits, available = 600f, reserved = 280f), 0f)
    }

    @Test
    fun `keyboard steps leave a snap point instead of being trapped by it`() {
        var size = 200f
        repeat(3) { size = SplitterMath.step(size, +1, limits, 400f).size }
        assertTrue("moved past the snap point: $size", size > 200f)
        assertEquals(248f, size, 0.01f)
    }

    @Test
    fun `keyboard steps stop at the bounds`() {
        assertEquals(400f, SplitterMath.step(395f, +1, limits, 400f).size, 0f)
        assertEquals(100f, SplitterMath.step(105f, -1, limits, 400f).size, 0f)
    }

    @Test
    fun `stored size falls back to the default when never dragged`() {
        val sizes = PaneSizes(explorer = 320f)
        assertEquals(320f, sizes.of(Pane.EXPLORER, 240f), 0f)
        assertEquals(260f, sizes.of(Pane.BOTTOM, 260f), 0f)
        assertEquals(500f, sizes.with(Pane.RIGHT, 500f).of(Pane.RIGHT, 0f), 0f)
    }
}
