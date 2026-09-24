package dev.easyide.app.ui.shell.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconTest {
    @Test fun `a stroked path inherits paint from the svg element`() {
        val icon = SvgIcon.parse("""<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 12h18"/></svg>""")!!
        val s = icon.shapes.single()
        assertEquals("M3 12h18", s.data)
        assertFalse(s.fill)
        assertTrue(s.stroke)
        assertEquals(2f, s.strokeWidth)
        assertTrue(s.round)
    }

    @Test fun `an unpainted svg fills its shapes, an element can override`() {
        val icon = SvgIcon.parse("""<svg viewBox="0 0 24 24"><path d="M0 0h4v4z"/><path fill="none" stroke="black" d="M1 1L2 2"/></svg>""")!!
        assertEquals(listOf(true, false), icon.shapes.map { it.fill })
        assertEquals(listOf(false, true), icon.shapes.map { it.stroke })
    }

    @Test fun `basic shapes become path data`() {
        val icon = SvgIcon.parse("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><rect x="2" y="3" width="5" height="6"/><line x1="0" y1="1" x2="2" y2="3"/><polyline points="1,2 3,4 5,6"/><polygon points="0,0 4,0 4,4"/></svg>""")!!
        assertEquals(5, icon.shapes.size)
        assertEquals("M8.0 12.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0Z", icon.shapes[0].data)
        assertEquals("M2.0 3.0h5.0v6.0h-5.0Z", icon.shapes[1].data)
        assertEquals("M0.0 1.0L2.0 3.0", icon.shapes[2].data)
        assertEquals("M1.0 2.0L3.0 4.0L5.0 6.0", icon.shapes[3].data)
        assertEquals("M0.0 0.0L4.0 0.0L4.0 4.0Z", icon.shapes[4].data)
    }

    @Test fun `degenerate shapes and non icons give nothing`() {
        assertNull(SvgIcon.parse("<p>no</p>"))
        assertNull(SvgIcon.parse("""<svg viewBox="0 0 24 24"><circle cx="1" cy="1" r="0"/><rect width="0" height="2"/><polyline points="1,2"/><path d=" "/></svg>"""))
    }

    @Test fun `single quoted attributes are read`() {
        val icon = SvgIcon.parse("<svg viewBox='0 0 24 24'><path d='M1 1h2'/></svg>")!!
        assertEquals("M1 1h2", icon.shapes.single().data)
    }
}
