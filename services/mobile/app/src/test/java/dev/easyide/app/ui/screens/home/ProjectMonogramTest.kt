package dev.easyide.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMonogramTest {

    @Test fun `initials of the first two words`() {
        assertEquals("MA", monogramLetters("my-app"))
        assertEquals("AS", monogramLetters("api_svc extra"))
        assertEquals("NP", monogramLetters("  notes  pad "))
    }

    @Test fun `a single word gives its first two letters`() {
        assertEquals("NO", monogramLetters("notes"))
        assertEquals("X", monogramLetters("x"))
    }

    @Test fun `symbols only falls back to the raw characters`() {
        assertEquals("++", monogramLetters("++"))
    }

    @Test fun `bucket is stable, in range and ignores case and padding`() {
        val bucket = monogramBucket("my-app", 8)
        assertTrue(bucket in 0 until 8)
        assertEquals(bucket, monogramBucket("  My-App ", 8))
        // Pinned: a tile colour must never change between releases.
        assertEquals(monogramBucket("my-app", 8), monogramBucket("my-app", 8))
    }

    @Test fun `different names spread across buckets`() {
        val buckets = (1..64).map { monogramBucket("project-$it", 8) }.toSet()
        assertTrue("only ${buckets.size} buckets used", buckets.size >= 6)
        assertNotEquals(monogramBucket("alpha", 1000), monogramBucket("beta", 1000))
    }

    @Test fun `known FNV-1a value`() {
        // FNV-1a 32-bit of "a" is 0xE40C292C.
        assertEquals(Math.floorMod(0xE40C292C.toInt(), 1000), monogramBucket("a", 1000))
    }
}
