package dev.easyide.app.ui.screens.workspace.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetTest {

    private fun parse(s: String, indent: String = "", vars: Map<String, String> = emptyMap()) =
        SnippetParser.parse(s, indent, tab = "    ", variable = vars::get)

    @Test
    fun tabStopsInOrderWithZeroLast() {
        val s = parse("for \${1:i} in \${2:range(10)}:\n\t$0")
        assertEquals("for i in range(10):\n    ", s.text)
        assertEquals(listOf(1, 2, 0), s.stops.map { it.index })
        assertEquals(OpenRange(4, 5), s.stops[0].ranges.single())
        assertEquals(OpenRange(9, 18), s.stops[1].ranges.single())
        assertEquals(s.text.length, s.finalOffset)
    }

    @Test
    fun nestedPlaceholdersAndMirrors() {
        val s = parse("\${1:foo \${2:bar}} $1")
        assertEquals("foo bar ", s.text)
        assertEquals(listOf(OpenRange(0, 7), OpenRange(8, 8)), s.stops[0].ranges)
        assertEquals(OpenRange(4, 7), s.stops[1].ranges.single())
    }

    @Test
    fun choicesInsertTheFirstOption() {
        val s = parse("log(\${1|info,warn\\,ing,error|})")
        assertEquals("log(info)", s.text)
        assertEquals(OpenRange(4, 8), s.stops.single().ranges.single())
    }

    @Test
    fun variablesDefaultsAndUnknownNames() {
        val s = parse("\$TM_FILENAME \${TM_LINE_NUMBER:1} \${UNKNOWN} \${NOPE:dflt}", vars = mapOf("TM_FILENAME" to "a.py"))
        assertEquals("a.py 1 UNKNOWN dflt", s.text)
    }

    @Test
    fun escapesAndLiteralDollars() {
        assertEquals("\$1 } \\ \$ a,b", parse("\\$1 \\} \\\\ \$ a,b").text)
        assertEquals("price \$", parse("price \$").text)
    }

    @Test
    fun transformsAreParsedAndDropped() {
        val s = parse("\${1:name} \${1/(.*)/\${1:/upcase}/}")
        assertEquals("name ", s.text)
        assertEquals(2, s.stops.single().ranges.size)
    }

    @Test
    fun newlinesAreIndentedToTheLine() {
        assertEquals("if x:\n        pass", parse("if x:\n\tpass", indent = "    ").text)
    }

    @Test
    fun sessionMovesAndTracksTypingInTheActiveField() {
        val snippet = parse("f(\${1:a}, \${2:b})$0")
        val session = requireNotNull(SnippetSession.start(snippet, at = 10))
        assertEquals(OpenRange(12, 13), session.active)
        // Replace "a" with "abc": the field grows, later fields shift.
        val typed = requireNotNull(session.shifted(12, 13, 15))
        assertEquals(OpenRange(12, 15), typed.active)
        val next = typed.next()
        assertEquals(OpenRange(17, 18), next.active)
        assertEquals(OpenRange(19, 19), next.next().active)
        // An edit outside every field ends the session.
        assertNull(typed.shifted(0, 1, 2))
    }

    @Test
    fun adjacentFieldOnlyMovesWhenTypingAtTheEndOfTheActiveOne() {
        val s = requireNotNull(SnippetSession.start(parse("\${1:a}\${2:b}"), at = 0))
        val typed = requireNotNull(s.shifted(1, 1, 2))
        assertEquals(OpenRange(0, 2), typed.stops[0].ranges.single())
        assertEquals(OpenRange(2, 3), typed.stops[1].ranges.single())
    }

    @Test
    fun snippetWithOnlyAFinalStopHasNoSession() {
        assertNull(SnippetSession.start(parse("print($0)"), at = 0))
        assertEquals(6, parse("print($0)").finalOffset)
    }
}
