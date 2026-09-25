package dev.easyide.app.ui.screens.workspace.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Test
import org.junit.Assert.assertEquals

class StyleCarryTest {

    private val red = SpanStyle(color = Color.Red)
    private val blue = SpanStyle(color = Color.Blue)

    private fun styled(text: String, vararg spans: Triple<SpanStyle, Int, Int>): AnnotatedString = buildAnnotatedString {
        append(text)
        spans.forEach { (style, start, end) -> addStyle(style, start, end) }
    }

    private fun ranges(s: AnnotatedString) = s.spanStyles.map { Triple(it.item, it.start, it.end) }.sortedBy { it.second }

    @Test fun `unchanged text keeps every span`() {
        val stale = styled("val x = 1", Triple(red, 0, 3), Triple(blue, 8, 9))
        assertEquals(ranges(stale), ranges(carryStyles(stale, "val x = 1")))
    }

    @Test fun `typing inside a coloured word stretches it`() {
        val stale = styled("val name = 1", Triple(red, 4, 8))
        val out = carryStyles(stale, "val nname = 1")
        assertEquals(listOf(Triple(red, 4, 9)), ranges(out))
    }

    @Test fun `text after the edit shifts with it`() {
        val stale = styled("ab cd", Triple(red, 0, 2), Triple(blue, 3, 5))
        val out = carryStyles(stale, "abXX cd")
        assertEquals(listOf(Triple(red, 0, 4), Triple(blue, 5, 7)), ranges(out))
    }

    @Test fun `deleting shrinks the span that covers it and moves later ones back`() {
        val stale = styled("abcdef gh", Triple(red, 0, 6), Triple(blue, 7, 9))
        val out = carryStyles(stale, "abef gh")
        assertEquals(listOf(Triple(red, 0, 4), Triple(blue, 5, 7)), ranges(out))
    }

    @Test fun `a span swallowed by a deletion is dropped`() {
        val stale = styled("a bb c", Triple(red, 2, 4), Triple(blue, 5, 6))
        val out = carryStyles(stale, "a  c")
        assertEquals(listOf(Triple(blue, 3, 4)), ranges(out))
    }

    @Test fun `a span partly overlapping the edit keeps its outside parts`() {
        val stale = styled("abcdefghij", Triple(red, 1, 4))
        val out = carryStyles(stale, "abcXghij")
        assertEquals(listOf(Triple(red, 1, 3)), ranges(out))
    }

    @Test fun `every carried range stays inside the new text`() {
        val stale = styled("one two three", Triple(red, 0, 3), Triple(blue, 4, 7), Triple(red, 8, 13))
        for (next in listOf("", "one", "one two", "x", "one two three four", "two three")) {
            carryStyles(stale, next).spanStyles.forEach {
                assert(it.start in 0 until it.end && it.end <= next.length) { "$next -> ${it.start}..${it.end}" }
            }
        }
    }
}
