package dev.easyide.ext.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class IgnoreRulesTest {
    private fun ignored(rules: String, vararg paths: String) = paths.map { IgnoreRules(rules.lines()).ignored(it) }

    @Test fun `unanchored names match at any depth`() {
        assertEquals(listOf(true, true, false), ignored("*.log", "a.log", "x/y/b.log", "a.log.txt"))
    }

    @Test fun `a slash anchors to the package root`() {
        assertEquals(listOf(true, false), ignored("/build/", "build/", "src/build/"))
        assertEquals(listOf(true, false), ignored("docs/draft.md", "docs/draft.md", "x/docs/draft.md"))
    }

    @Test fun `directory rules skip files of that name`() {
        assertEquals(listOf(true, false, true), ignored("cache/", "cache/", "cache", "cache/a.bin"))
    }

    @Test fun `double star and negation, last rule wins`() {
        assertEquals(listOf(true, true, false), ignored("assets/**/*.psd\n!assets/keep/*.psd", "assets/a.psd", "assets/x/y/b.psd", "assets/keep/c.psd"))
    }

    @Test fun `comments and blank lines are ignored`() {
        assertEquals(listOf(false), ignored("# *.md\n\n", "README.md"))
    }
}
