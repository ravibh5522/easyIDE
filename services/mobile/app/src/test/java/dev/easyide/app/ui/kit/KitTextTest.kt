package dev.easyide.app.ui.kit

import androidx.compose.ui.text.font.FontWeight
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.FontPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitTextTest {

    private fun every(check: (Density, FontPairing, KitText) -> Unit) {
        for (d in Density.entries) for (p in FontPairing.entries) check(d, p, KitText.of(d, p))
    }

    @Test fun `every role is at least 11sp with a line height of at least 1_15 times its size`() = every { d, p, text ->
        text.all.forEach {
            assertTrue("$d $p ${it.fontSize}", it.fontSize.value >= 11f)
            assertTrue("$d $p ${it.fontSize}", it.lineHeight.value >= it.fontSize.value * KitText.MIN_LINE_RATIO)
        }
    }

    @Test fun `a density uses at most six distinct sizes`() = every { d, _, text ->
        assertTrue("$d", text.all.map { it.fontSize.value }.toSet().size <= 6)
    }

    @Test fun `dense is 13 12 11 for body caption label and comfortable is 14 13 12`() {
        val dense = KitText.of(Density.DENSE, FontPairing.GEIST)
        assertEquals(listOf(13f, 12f, 11f, 15f, 20f), dense.run { listOf(body, caption, label, heading, display) }.map { it.fontSize.value })
        val comfortable = KitText.of(Density.COMFORTABLE, FontPairing.GEIST)
        assertEquals(listOf(14f, 13f, 12f, 16f, 20f), comfortable.run { listOf(body, caption, label, heading, display) }.map { it.fontSize.value })
    }

    @Test fun `sizes never shrink from dense to comfortable to spacious`() {
        fun sizes(d: Density) = KitText.of(d, FontPairing.GEIST).all.map { it.fontSize.value }
        sizes(Density.DENSE).zip(sizes(Density.COMFORTABLE)).zip(sizes(Density.SPACIOUS)).forEach { (a, c) ->
            assertTrue(a.first <= a.second && a.second <= c)
        }
    }

    @Test fun `title is medium, body regular and only the label is tracked`() {
        val text = KitText.of(Density.DENSE, FontPairing.GEIST)
        assertEquals(FontWeight.Medium, text.title.fontWeight)
        assertEquals(FontWeight.Normal, text.body.fontWeight)
        assertEquals(text.title.fontSize, text.body.fontSize)
        assertNotEquals(text.body.letterSpacing, text.label.letterSpacing)
    }

    @Test fun `the mono roles use the chrome monospace at the body and caption size`() {
        val text = KitText.of(Density.DENSE, FontPairing.GEIST)
        assertEquals(text.body.fontSize, text.mono.fontSize)
        assertEquals(text.caption.fontSize, text.monoSmall.fontSize)
        assertNotEquals(text.body.fontFamily, text.mono.fontFamily)
    }
}
