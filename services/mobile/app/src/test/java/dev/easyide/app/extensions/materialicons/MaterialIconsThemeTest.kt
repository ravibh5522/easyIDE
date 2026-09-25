package dev.easyide.app.extensions.materialicons

import dev.easyide.app.extensions.ActiveIconTheme
import dev.easyide.app.extensions.adapters.IconTheme
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendored Material Icon Theme: every mapping resolves to a real, small SVG, and the common project files get their own icon. */
class MaterialIconsThemeTest {

    private val pack = MaterialIconsPack

    private fun name(path: String?) = path?.substringAfterLast('/')?.removeSuffix(".svg")

    private fun file(t: IconTheme, n: String, light: Boolean = false, lang: String? = null) = name(t.fileIcon(n, lang, light))

    @Test fun `every mapping points at a defined icon whose svg exists`() {
        val missing = pack.references().filter { (_, id) -> pack.definitions[id]?.isFile != true }
        assertEquals(emptyList<Pair<String, String>>(), missing)
    }

    @Test fun `every shipped svg is used by some mapping and within the size limit`() {
        val used = pack.references().map { it.second }.toSet()
        assertEquals(emptyList<String>(), pack.definitions.keys.filter { it !in used })
        val tooBig = pack.definitions.filter { it.value.length() > ActiveIconTheme.ICON_FILE_MAX_BYTES }.keys
        assertEquals(emptySet<String>(), tooBig)
        assertTrue(pack.themeFile.length() <= ActiveIconTheme.ICON_THEME_FILE_MAX_BYTES)
        val total = pack.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("pack is $total bytes", total < 4L * 1024 * 1024)
    }

    @Test fun `the parsed theme keeps every definition`() {
        assertEquals(pack.definitions.size, pack.theme.icons.size)
    }

    @Test fun `common project files and folders resolve to their own icons`() {
        val t = pack.theme
        val generic = name(t.fileIcon("some.unknownext", null, false))
        assertEquals("file", generic)
        assertEquals("python", file(t, "main.py", lang = "python"))
        assertEquals("docker", file(t, "Dockerfile"))
        for (n in listOf("pyproject.toml", "README.md", ".gitignore", "package.json", "main.py", "Dockerfile", "build.gradle.kts", "index.ts", "style.css")) {
            assertNotEquals("$n resolves to the default file icon", generic, file(t, n))
        }
        val folder = name(t.folderIcon("plain-folder", expanded = false, light = false))
        assertEquals("folder", folder)
        for (n in listOf("src", "node_modules", ".github", "docs", "test", "assets")) {
            assertNotEquals("folder $n", folder, name(t.folderIcon(n, expanded = false, light = false)))
            assertNotNull(t.folderIcon(n, expanded = true, light = false))
        }
        assertEquals("folder-open", name(t.folderIcon("plain-folder", expanded = true, light = false)))
        assertEquals("folder-root", name(t.rootFolderIcon(expanded = false, light = false)))
    }

    @Test fun `the light section overrides dark-only glyphs and falls back to the root section`() {
        val t = pack.theme
        val lightExt = (pack.raw["light"] as JsonObject)["fileExtensions"] as JsonObject
        assertTrue(lightExt.isNotEmpty())
        assertEquals("toml_light", file(t, "a.toml", light = true))
        assertNotEquals(file(t, "a.toml", light = false), file(t, "a.toml", light = true))
        assertEquals(file(t, "a.py", light = false), file(t, "a.py", light = true))
    }
}
