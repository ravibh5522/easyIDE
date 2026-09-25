package dev.easyide.app.extensions.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IconThemesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = """{
      // comments are allowed, as in VS Code
      "iconDefinitions": {
        "_file": {"iconPath": "./icons/file.png"},
        "_ts": {"iconPath": "./icons/ts.png"},
        "_dts": {"iconPath": "./icons/dts.png"},
        "_py": {"iconPath": "./icons/python.png"},
        "_make": {"iconPath": "./icons/make.png"},
        "_folder": {"iconPath": "./icons/folder.png"},
        "_folder_open": {"iconPath": "./icons/folder-open.png"},
        "_src": {"iconPath": "./icons/src.png"},
        "_src_open": {"iconPath": "./icons/src-open.png"},
        "_light_ts": {"iconPath": "./icons/ts-light.png"},
        "_svg": {"iconPath": "./icons/vector.svg"},
        "_escape": {"iconPath": "../../outside.png"},
        "_font": {"fontCharacter": "\\E001"}
      },
      "file": "_file",
      "folder": "_folder",
      "folderExpanded": "_folder_open",
      "fileExtensions": {"ts": "_ts", "d.ts": "_dts", "svgext": "_svg", "bad": "_escape"},
      "fileNames": {"Makefile": "_make"},
      "languageIds": {"python": "_py"},
      "folderNames": {"src": "_src"},
      "folderNamesExpanded": {"src": "_src_open"},
      "light": {"fileExtensions": {"ts": "_light_ts"}}
    }"""

    private fun theme(): Pair<IconTheme, List<String>> {
        val ext = tmp.newFolder("ext")
        val themeFile = File(ext, "theme/icons.json").apply { parentFile!!.mkdirs(); writeText(json) }
        val skipped = ArrayList<String>()
        return IconThemeFile.parse("my-icons", json, themeFile, ext) { skipped += it }!! to skipped
    }

    private fun IconTheme.fileName(name: String, lang: String? = null, light: Boolean = false) = fileIcon(name, lang, light)?.let { File(it).name }

    @Test
    fun fileLookupPrecedence() {
        val (t, _) = theme()
        assertEquals("make.png", t.fileName("makefile", lang = "makefile"))
        assertEquals("dts.png", t.fileName("index.d.ts"))
        assertEquals("ts.png", t.fileName("App.TS"))
        assertEquals("python.png", t.fileName("tool", lang = "python"))
        assertEquals("file.png", t.fileName("notes.txt"))
    }

    @Test
    fun lightSectionWinsOnLightBases() {
        val (t, _) = theme()
        assertEquals("ts-light.png", t.fileName("a.ts", light = true))
        assertEquals("dts.png", t.fileName("a.d.ts", light = true))
        assertEquals("ts.png", t.fileName("a.ts", light = false))
    }

    @Test
    fun folderLookup() {
        val (t, _) = theme()
        assertEquals("src.png", t.folderIcon("SRC", expanded = false, light = false)?.let { File(it).name })
        assertEquals("src-open.png", t.folderIcon("src", expanded = true, light = false)?.let { File(it).name })
        assertEquals("folder-open.png", t.folderIcon("lib", expanded = true, light = false)?.let { File(it).name })
        assertEquals("folder.png", t.folderIcon("lib", expanded = false, light = false)?.let { File(it).name })
    }

    @Test
    fun fontAndEscapingIconsAreDropped() {
        val (t, skipped) = theme()
        assertEquals(emptyList<String>(), skipped)
        assertEquals("vector.svg", t.fileName("x.svgext"))
        assertEquals("file.png", t.fileName("x.bad"))
        assertNull(t.icons["_font"])
    }

    @Test
    fun multiPartExtensionsLongestFirst() {
        assertEquals(listOf("test.d.ts", "d.ts", "ts"), IconTheme.extensionsOf("a.test.d.ts"))
        assertEquals(emptyList<String>(), IconTheme.extensionsOf("makefile"))
    }

    @Test
    fun notAnObjectIsNull() {
        val ext = tmp.newFolder("e2")
        assertNull(IconThemeFile.parse("x", "[1, 2]", File(ext, "t.json"), ext))
    }
}
