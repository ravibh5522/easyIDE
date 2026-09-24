package dev.easyide.extensions.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(s: String) = SemVer.parse(s) ?: error("bad version $s")
    private fun r(s: String) = SemVerRange.parse(s) ?: error("bad range $s")

    @Test fun `parses core and prerelease`() {
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 0, 0, listOf("alpha", "1")), v("1.0.0-alpha.1"))
    }

    @Test fun `rejects build metadata, leading zeros and partials`() {
        listOf("1.0.0+build", "01.0.0", "1.0", "1.0.0-", "1.0.0-01", "a.b.c", "1.0.0.0", "", "99999999999.0.0").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }

    @Test fun `precedence follows semver 2`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", v(a) < v(b)) }
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3")))
    }

    @Test fun `caret ranges below 1 are minor-bounded`() {
        assertTrue(r("^0.3.0").contains(v("0.3.0")))
        assertTrue(r("^0.3.0").contains(v("0.3.9")))
        assertFalse(r("^0.3.0").contains(v("0.4.0")))
        assertFalse(r("^0.3.0").contains(v("0.2.9")))
        assertTrue(r("^0.0.3").contains(v("0.0.3")))
        assertFalse(r("^0.0.3").contains(v("0.0.4")))
        assertTrue(r("^1.2.3").contains(v("1.9.0")))
        assertFalse(r("^1.2.3").contains(v("2.0.0")))
    }

    @Test fun `tilde, x ranges, comparators, hyphen and or`() {
        assertTrue(r("~1.2.3").contains(v("1.2.9")))
        assertFalse(r("~1.2.3").contains(v("1.3.0")))
        assertTrue(r("~1").contains(v("1.9.9")))
        assertTrue(r("1.x").contains(v("1.5.0")))
        assertFalse(r("1.x").contains(v("2.0.0")))
        assertTrue(r("*").contains(v("7.0.0")))
        assertTrue(r(">=1.0.0 <2.0.0").contains(v("1.5.0")))
        assertFalse(r(">=1.0.0 <2.0.0").contains(v("2.0.0")))
        assertTrue(r(">= 1.2").contains(v("1.2.0")))
        assertTrue(r("<=1.2").contains(v("1.2.9")))
        assertFalse(r("<=1.2").contains(v("1.3.0")))
        assertTrue(r(">1.2").contains(v("1.3.0")))
        assertFalse(r(">1.2").contains(v("1.2.5")))
        assertTrue(r("1.0.0 - 1.2").contains(v("1.2.7")))
        assertFalse(r("1.0.0 - 1.2").contains(v("1.3.0")))
        assertTrue(r("^0.2.0 || ^0.3.0").contains(v("0.3.1")))
        assertTrue(r("=0.3.0").contains(v("0.3.0")))
        assertFalse(r("0.3.0").contains(v("0.3.1")))
    }

    @Test fun `prereleases match only a same-core prerelease comparator`() {
        assertFalse(r("^0.3.0").contains(v("0.3.5-beta")))
        assertTrue(r(">=0.3.5-alpha").contains(v("0.3.5-beta")))
        assertFalse(r(">=0.3.5-alpha").contains(v("0.3.6-beta")))
    }

    @Test fun `invalid ranges are null`() {
        listOf("", "  ", "^", "1.x.3", "abc", ">=1.0.0 <", "1.0-beta").forEach { assertNull(it, SemVerRange.parse(it)) }
        assertNotNull(SemVerRange.parse("^0.3.0"))
    }

    @Test fun `extension ids are lower-cased and validated`() {
        assertEquals("acme.python", ExtensionId.parse("Acme.Python")?.value)
        assertNull(ExtensionId.parse("acme.my.pack"))
        assertEquals("acme", ExtensionId.parse("acme.pack")?.publisher)
        assertNull(ExtensionId.parse("noDot"))
        assertNull(ExtensionId.parse("-bad.name"))
        assertNull(ExtensionId.of("acme", "a".repeat(64)))
    }
}
