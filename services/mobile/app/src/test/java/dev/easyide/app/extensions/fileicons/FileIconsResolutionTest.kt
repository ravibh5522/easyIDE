package dev.easyide.app.extensions.fileicons

import dev.easyide.app.extensions.adapters.IconAssociations
import dev.easyide.app.extensions.adapters.IconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** VS Code lookup order on a small theme, then the same rules on the shipped one. */
class FileIconsResolutionTest {

    private val small = IconTheme(
        id = "t",
        icons = listOf("file", "folder", "folder-open", "root", "root-open", "name", "ts", "dts", "test", "spec", "lang", "src", "src-open", "ts-light", "src-light", "src-open-light")
            .associateWith { "/$it.svg" },
        dark = IconAssociations(
            file = "file", folder = "folder", folderExpanded = "folder-open", rootFolder = "root", rootFolderExpanded = "root-open",
            fileNames = mapOf("package.json" to "name"),
            fileExtensions = mapOf("ts" to "ts", "d.ts" to "dts", "test.tsx" to "test", "spec.js" to "spec"),
            languageIds = mapOf("kotlin" to "lang"),
            folderNames = mapOf("src" to "src"), folderNamesExpanded = mapOf("src" to "src-open"),
        ),
        light = IconAssociations(
            fileExtensions = mapOf("ts" to "ts-light"), folderNames = mapOf("src" to "src-light"), folderNamesExpanded = mapOf("src" to "src-open-light"),
        ),
    )

    private fun file(name: String, lang: String? = null, light: Boolean = false) = small.fileIcon(name, lang, light)?.removePrefix("/")?.removeSuffix(".svg")

    @Test fun `file names beat extensions beat language ids beat the default`() {
        assertEquals("name", file("package.json", lang = "kotlin"))
        assertEquals("ts", file("a.ts", lang = "kotlin"))
        assertEquals("lang", file("a.kts", lang = "kotlin"))
        assertEquals("file", file("a.unknown"))
    }

    @Test fun `the longest extension suffix wins and matching ignores case`() {
        assertEquals("dts", file("index.d.ts"))
        assertEquals("dts", file("INDEX.D.TS"))
        assertEquals("test", file("App.test.tsx"))
        assertEquals("spec", file("a.b.spec.js"))
        assertEquals("ts", file("a.spec.ts"))
        assertEquals("name", file("PACKAGE.JSON"))
    }

    @Test fun `folders resolve by name then by the generic pair`() {
        assertEquals("/src.svg", small.folderIcon("SRC", expanded = false, light = false))
        assertEquals("/src-open.svg", small.folderIcon("src", expanded = true, light = false))
        assertEquals("/folder.svg", small.folderIcon("other", expanded = false, light = false))
        assertEquals("/folder-open.svg", small.folderIcon("other", expanded = true, light = false))
        assertEquals("/root.svg", small.rootFolderIcon(expanded = false, light = false))
        assertEquals("/root-open.svg", small.rootFolderIcon(expanded = true, light = false))
    }

    @Test fun `light overrides apply on light bases only and fall back to the root section`() {
        assertEquals("ts-light", file("a.ts", light = true))
        assertEquals("ts", file("a.ts", light = false))
        assertEquals("dts", file("a.d.ts", light = true))
        assertEquals("/src-light.svg", small.folderIcon("src", expanded = false, light = true))
        assertEquals("/src-open-light.svg", small.folderIcon("src", expanded = true, light = true))
    }

    @Test fun `the shipped theme follows the same order`() {
        val t = FileIconsPack.theme
        fun name(n: String, light: Boolean = false) = t.fileIcon(n, null, light)
        assertNotEquals(name("a.ts"), name("a.d.ts"))
        assertNotEquals(name("a.ts"), name("a.test.ts"))
        assertNotEquals(name("a.js"), name("a.spec.js"))
        assertNotEquals(name("a.tsx"), name("a.test.tsx"))
        assertEquals(name("Dockerfile"), name("dockerfile"))
        assertNotEquals("a file name beats its extension", name("package.json"), name("a.json"))
        assertNotEquals(name("package.json"), name("package-lock.json"))
        assertEquals("unknown falls to the generic file", t.icons["file"], name("a.zzzzz"))
        assertEquals(t.icons["file"], name("noextension"))
    }

    @Test fun `the shipped theme has folder rules with open variants and light overrides`() {
        val t = FileIconsPack.theme
        for (n in listOf("src", "lib", "test", "tests", "docs", "assets", "images", "node_modules", ".git", ".github", ".vscode", "build", "dist", "out", "target", "bin", "config", "scripts", "components", "public", "vendor")) {
            val closed = t.folderIcon(n, expanded = false, light = false)
            val open = t.folderIcon(n, expanded = true, light = false)
            assertNotEquals("$n is not the generic folder", t.folderIcon("zz-none", false, false), closed)
            assertNotEquals("$n open differs from closed", closed, open)
        }
        assertNotEquals(t.folderIcon("zz-none", false, false), t.folderIcon("zz-none", true, false))
        assertNotEquals(t.folderIcon("zz-none", false, false), t.rootFolderIcon(false, false))
        // Kotlin's colour is a single fill for both modes; JavaScript yellow needs a darker light-mode variant.
        assertNotEquals(t.fileIcon("a.js", null, light = false), t.fileIcon("a.js", null, light = true))
        assertEquals(t.fileIcon("a.kt", null, light = false), t.fileIcon("a.kt", null, light = true))
    }
}
