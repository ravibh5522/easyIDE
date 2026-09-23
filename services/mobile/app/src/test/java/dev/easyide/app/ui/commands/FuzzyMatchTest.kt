package dev.easyide.app.ui.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatchTest {

    @Test
    fun subsequenceCaseInsensitive() {
        assertNotNull(fuzzyScore("tgtrm", "Toggle Terminal"))
        assertNotNull(fuzzyScore("SAVE", "save all"))
        assertNull(fuzzyScore("xyz", "Toggle Terminal"))
        assertNull(fuzzyScore("mt", "Toggle Terminal".take(6)))
    }

    @Test
    fun wordStartsRankHigher() {
        val items = listOf("Next editor tab", "Toggle terminal")
        assertEquals("Toggle terminal", fuzzyFilter("tt", items) { it }.first())
    }

    @Test
    fun blankQueryKeepsOrder() {
        val items = listOf("b", "a")
        assertEquals(items, fuzzyFilter("  ", items) { it })
    }
}
