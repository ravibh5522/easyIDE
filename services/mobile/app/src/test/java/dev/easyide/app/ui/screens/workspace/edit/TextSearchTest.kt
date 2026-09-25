package dev.easyide.app.ui.screens.workspace.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private fun found(text: String, query: SearchQuery): List<String> {
        val result = TextSearch.find(text, query) as SearchResult.Found
        return result.matches.map { text.substring(it.start, it.end) }
    }

    private fun q(text: String, case: Boolean = false, word: Boolean = false, regex: Boolean = false) =
        SearchQuery(text, caseSensitive = case, wholeWord = word, regex = regex)

    @Test
    fun `literal search ignores case by default and honours it on request`() {
        assertEquals(listOf("Foo", "foo", "FOO"), found("Foo foo FOO", q("foo")))
        assertEquals(listOf("foo"), found("Foo foo FOO", q("foo", case = true)))
    }

    @Test
    fun `regex metacharacters in a literal query are literal`() {
        assertEquals(listOf("a.b"), found("a.b axb", q("a.b")))
        assertEquals(listOf("(x)"), found("f(x) g", q("(x)")))
    }

    @Test
    fun `whole word does not match inside identifiers`() {
        assertEquals(listOf("id"), found("id identity my_id id2 (id)", q("id", word = true)).take(1))
        val text = "id identity my_id id2 (id) x.id"
        val hits = (TextSearch.find(text, q("id", word = true)) as SearchResult.Found).matches.map { it.start }
        assertEquals(listOf(0, 23, 29), hits)
    }

    @Test
    fun `whole word treats non-ascii letters as word characters`() {
        assertEquals(emptyList<String>(), found("caf\u00e9s", q("caf", word = true)))
        assertEquals(listOf("caf"), found("caf ok", q("caf", word = true)))
    }

    @Test
    fun `regex mode matches per line anchors`() {
        assertEquals(listOf("a", "a"), found("a1\nb\na2", q("^a", regex = true)))
        assertEquals(listOf("1", "2"), found("a1\nb\na2", q("\\d\$", regex = true)))
    }

    @Test
    fun `an invalid regex reports its problem instead of throwing`() {
        val result = TextSearch.find("abc", q("(unclosed", regex = true))
        assertTrue(result is SearchResult.InvalidPattern)
        assertTrue((result as SearchResult.InvalidPattern).message.isNotEmpty())
    }

    @Test
    fun `an empty query finds nothing`() {
        assertEquals(SearchResult.Found(emptyList(), false), TextSearch.find("abc", q("")))
    }

    @Test
    fun `empty matches are skipped`() {
        assertEquals(listOf("aa", "a"), found("aabxa", q("a*", regex = true)))
    }

    @Test
    fun `matches are capped and the result says so`() {
        val text = "a".repeat(TextSearch.MAX_MATCHES + 50)
        val result = TextSearch.find(text, q("a")) as SearchResult.Found
        assertEquals(TextSearch.MAX_MATCHES, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `a catastrophic pattern is stopped by the deadline`() {
        val text = "a".repeat(45)
        val started = System.nanoTime()
        val result = TextSearch.find(text, q("(.*a){20}b", regex = true), timeoutMs = 50)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(SearchResult.TimedOut, result)
        assertTrue("stopped in $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun `replace all in literal mode inserts the replacement verbatim`() {
        val r = TextSearch.replaceAll("a.b a.b", q("a.b"), "\$1\\n") as ReplaceResult.Replaced
        assertEquals("\$1\\n \$1\\n", r.text)
        assertEquals(2, r.count)
    }

    @Test
    fun `replace all in regex mode expands groups and escapes`() {
        val r = TextSearch.replaceAll("k1=v1;k2=v2", q("(\\w+)=(\\w+)", regex = true), "\$2:\$1") as ReplaceResult.Replaced
        assertEquals("v1:k1;v2:k2", r.text)
        assertEquals(2, r.count)
        val nl = TextSearch.replaceAll("a,b", q(",", regex = true), "\\n") as ReplaceResult.Replaced
        assertEquals("a\nb", nl.text)
        val dollar = TextSearch.replaceAll("a", q("a", regex = true), "\$\$") as ReplaceResult.Replaced
        assertEquals("\$", dollar.text)
    }

    @Test
    fun `a group reference past the last group stays literal`() {
        val r = TextSearch.replaceAll("ab", q("(a)b", regex = true), "\$1\$5") as ReplaceResult.Replaced
        assertEquals("a\$5", r.text)
    }

    @Test
    fun `replace all reports zero when nothing matched and leaves the text alone`() {
        val r = TextSearch.replaceAll("abc", q("z"), "y") as ReplaceResult.Replaced
        assertEquals(0, r.count)
        assertEquals("abc", r.text)
    }

    @Test
    fun `replace all fails on an invalid pattern and on a timeout`() {
        assertTrue(TextSearch.replaceAll("a", q("(", regex = true), "x") is ReplaceResult.Failed)
        val slow = TextSearch.replaceAll("a".repeat(45), q("(.*a){20}b", regex = true), "z", timeoutMs = 50)
        assertEquals(ReplaceResult.Failed(SearchResult.TimedOut), slow)
    }

    @Test
    fun `replacement for one match expands groups and detects a stale match`() {
        val text = "k1=v1"
        val query = q("(\\w+)=(\\w+)", regex = true)
        val match = (TextSearch.find(text, query) as SearchResult.Found).matches.single()
        assertEquals("v1-k1", TextSearch.replacementFor(text, query, match, "\$2-\$1"))
        assertNull(TextSearch.replacementFor("zz$text", query, match, "x"))
        assertEquals("plain", TextSearch.replacementFor(text, q("k1"), Match(0, 2), "plain"))
    }

    @Test
    fun `navigation wraps and starts from the caret`() {
        val m = listOf(Match(2, 4), Match(10, 12), Match(20, 22))
        assertEquals(1, TextSearch.firstFrom(m, 5))
        assertEquals(1, TextSearch.firstFrom(m, 10))
        assertEquals(0, TextSearch.firstFrom(m, 23))
        assertEquals(-1, TextSearch.firstFrom(emptyList(), 0))
        assertEquals(0, TextSearch.lastBefore(m, 5))
        assertEquals(2, TextSearch.lastBefore(m, 1))
        assertEquals(1, TextSearch.step(3, 0, forward = true))
        assertEquals(0, TextSearch.step(3, 2, forward = true))
        assertEquals(2, TextSearch.step(3, 0, forward = false))
        assertEquals(-1, TextSearch.step(0, -1, forward = true))
        assertEquals(2, TextSearch.step(3, -1, forward = false))
    }
}
