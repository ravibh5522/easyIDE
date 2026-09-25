package dev.easyide.app.ui.kit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Empty-state art is data in the string resources; this pins its shape (identity.md 10: five lines at most, muted ASCII). */
class EmptyArtTest {
    // Unit tests run with the module directory as working directory.
    private val art: Map<String, List<String>> = File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") }!!
        .flatMap { f -> Regex("""<string name="(kit_art_\w+)"[^>]*>"(.*?)"</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).toList() }
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\n", "\n").split("\n") }

    private val names = EmptyArt.entries.map { "kit_art_" + it.name.lowercase() }

    @Test fun `every art variant has a picture and every picture a variant`() {
        assertEquals(names.toSet(), art.keys)
    }

    @Test fun `each picture is one to five lines of narrow ASCII`() {
        art.forEach { (name, lines) ->
            assertTrue(name, lines.size in 1..5)
            lines.forEach {
                assertTrue("$name: $it", it.length <= MAX_COLUMNS)
                assertTrue("$name: $it", it.all { c -> c.code in 32..126 })
            }
        }
    }

    @Test fun `no two variants draw the same picture`() {
        assertEquals(art.size, art.values.toSet().size)
    }

    private companion object {
        const val MAX_COLUMNS = 16
    }
}
