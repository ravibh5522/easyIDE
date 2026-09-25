package dev.easyide.app.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import dev.easyide.app.ui.props.IconStyle
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EiIconsTest {
    /** The tokens packs and the shell may rely on; renaming one is a breaking change. */
    private val names = listOf(
        "prompt", "cursor", "palette", "settings", "swatch", "keycap", "density", "dock",
        "project", "new_project", "terminal", "language_server", "agent",
        "branch", "commit", "diff", "stash", "sync",
        "sandbox", "environment", "root", "extension_pack", "install", "theme",
    )

    private class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** Walks the M/L/H/V/Z nodes into absolute segments and points; anything else fails the test. */
    private fun walk(nodes: List<PathNode>): Pair<List<Segment>, List<Pair<Float, Float>>> {
        val segments = mutableListOf<Segment>()
        val points = mutableListOf<Pair<Float, Float>>()
        var x = 0f; var y = 0f; var startX = 0f; var startY = 0f
        fun to(nx: Float, ny: Float) {
            segments += Segment(x, y, nx, ny); x = nx; y = ny; points += x to y
        }
        for (n in nodes) when (n) {
            is PathNode.MoveTo -> { x = n.x; y = n.y; startX = x; startY = y; points += x to y }
            is PathNode.RelativeMoveTo -> { x += n.dx; y += n.dy; startX = x; startY = y; points += x to y }
            is PathNode.LineTo -> to(n.x, n.y)
            is PathNode.RelativeLineTo -> to(x + n.dx, y + n.dy)
            is PathNode.HorizontalTo -> to(n.x, y)
            is PathNode.RelativeHorizontalTo -> to(x + n.dx, y)
            is PathNode.VerticalTo -> to(x, n.y)
            is PathNode.RelativeVerticalTo -> to(x, y + n.dy)
            PathNode.Close -> { if (x != startX || y != startY) to(startX, startY) }
            else -> error("only M, L, H, V and Z are allowed, found $n")
        }
        return segments to points
    }

    private val geometry = EiIcons.all.flatMap { g -> listOf(g to g.outlineNodes, g to g.fillNodes) }

    @Test fun `the set is exactly the 24 named glyphs in order`() {
        assertEquals(names, EiIcons.all.map { it.name })
        assertEquals(24, EiIcons.all.size)
    }

    @Test fun `names are unique lower snake case`() {
        assertEquals(EiIcons.all.size, EiIcons.all.map { it.name }.toSet().size)
        EiIcons.all.forEach { assertTrue(it.name, it.name.matches(Regex("[a-z]+(_[a-z]+)*"))) }
    }

    @Test fun `every glyph draws something and every path parses`() {
        EiIcons.all.forEach { g ->
            assertTrue(g.name, g.outline.isNotEmpty() || g.fill.isNotEmpty())
            assertTrue(g.name, g.outlineNodes.size + g.fillNodes.size > 1)
        }
    }

    @Test fun `path data is ASCII`() {
        EiIcons.all.forEach { g -> assertTrue(g.name, (g.outline + g.fill).all { it.code < 128 }) }
    }

    @Test fun `every point stays inside the 20 live area of the 24 grid`() {
        geometry.forEach { (g, nodes) ->
            walk(nodes).second.forEach { (px, py) ->
                assertTrue("${g.name} x=$px", px in EiGlyph.LIVE_MIN..EiGlyph.LIVE_MAX)
                assertTrue("${g.name} y=$py", py in EiGlyph.LIVE_MIN..EiGlyph.LIVE_MAX)
            }
        }
    }

    @Test fun `every segment is horizontal, vertical or 45 degrees`() {
        geometry.forEach { (g, nodes) ->
            walk(nodes).first.forEach { s ->
                val dx = abs(s.x1 - s.x0); val dy = abs(s.y1 - s.y0)
                assertTrue("${g.name} ${s.x0},${s.y0} to ${s.x1},${s.y1}", dx == 0f || dy == 0f || dx == dy)
            }
        }
    }

    @Test fun `filled shapes are closed`() {
        EiIcons.all.forEach { g ->
            val nodes = g.fillNodes
            assertEquals(g.name, nodes.count { it is PathNode.MoveTo || it is PathNode.RelativeMoveTo }, nodes.count { it == PathNode.Close })
        }
    }

    @Test fun `the vector is a 24 by 24 monochrome mask`() {
        EiIcons.all.forEach { g ->
            val v = g.vector
            assertEquals(g.name, 24f, v.viewportWidth, 0f)
            assertEquals(g.name, 24f, v.viewportHeight, 0f)
            assertEquals(g.name, "ei_${g.name}", v.name)
        }
        assertSame(EiIcons.all[0].vector, EiIcons.vector("prompt"))
    }

    @Test fun `ei style prefers the custom glyph and material style the Material one`() {
        assertSame(EiIcons.vector("terminal"), resolveIconOrNull("terminal", IconStyle.EI))
        assertSame(MATERIAL_ICONS.getValue("terminal"), resolveIconOrNull("terminal", IconStyle.MATERIAL))
    }

    @Test fun `aliases reach the custom glyph`() {
        assertSame(EiIcons.vector("extension_pack"), resolveIconOrNull("extensions", IconStyle.EI))
        assertSame(EiIcons.vector("branch"), resolveIconOrNull("git", IconStyle.EI))
        assertSame(EiIcons.vector("project"), resolveIconOrNull("files", IconStyle.EI))
    }

    @Test fun `a glyph without a Material stand-in still shows under the material style`() {
        assertNotNull(resolveIconOrNull("cursor", IconStyle.MATERIAL))
        assertSame(EiIcons.vector("cursor"), resolveIconOrNull("cursor", IconStyle.MATERIAL))
    }

    @Test fun `generic verbs stay Material under both styles`() {
        listOf("close", "add", "search", "more", "back", "check").forEach {
            IconStyle.entries.forEach { style -> assertSame(it, MATERIAL_ICONS.getValue(it), resolveIconOrNull(it, style)) }
        }
    }

    @Test fun `unknown token is null or the fallback`() {
        IconStyle.entries.forEach {
            assertNull(resolveIconOrNull("no-such-glyph", it))
            assertSame(UNKNOWN_ICON, resolveIcon("no-such-glyph", it))
        }
    }

    @Test fun `every glyph has a Material stand-in except the cursor`() {
        EiIcons.all.filter { it.name != "cursor" }.forEach { assertNotNull(it.name, MATERIAL_ICONS[it.name]) }
    }
}
