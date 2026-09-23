package dev.easyide.sandbox.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionNamesTest {

    @Test fun `ids are canonicalised to lower case`() {
        assertEquals("easyide.python", ExtensionId.parse("EasyIDE.Python").value)
        assertEquals(ExtensionId.parse("a.b"), ExtensionId.parse("A.B"))
    }

    @Test fun `ids that could escape or collide as paths are rejected`() {
        listOf(
            "", ".", "..", "../x.y", "a/b.c", "a.b/c", "/a.b", "a..b", "a.b.c", "-a.b", "a.-b",
            "a", "a.", ".a", "a b.c", "a.b\u0000", "a\\b.c", "K.b", "p." + "n".repeat(64),
        ).forEach { raw ->
            assertNull(raw, ExtensionId.parseOrNull(raw))
        }
        assertThrows(IllegalArgumentException::class.java) { ExtensionId.parse("../etc") }
    }

    @Test fun `longest legal segments are accepted`() {
        assertNotNull(ExtensionId.parseOrNull("p".repeat(63) + "." + "n".repeat(63)))
    }

    @Test fun `semver without build metadata is accepted`() {
        listOf("0.0.0", "1.2.3", "10.20.30", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-0.3.7", "1.0.0-x-y.z")
            .forEach { assertEquals(it, ExtensionVersion.parse(it).value) }
    }

    @Test fun `versions that are not strict semver or could be path segments are rejected`() {
        listOf(
            "", ".", "..", "current", "1", "1.2", "01.2.3", "1.2.3+build", "1.2.3-01",
            "1.2.3-", "1.2.3/..", "../1.2.3", ".tmp-1.2.3-9", "v1.2.3", "1.2.3 ",
        ).forEach { raw ->
            assertNull(raw, ExtensionVersion.parseOrNull(raw))
        }
    }
}
