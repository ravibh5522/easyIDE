package dev.easyide.app.extensions.adapters

import androidx.compose.ui.graphics.Color
import dev.easyide.app.extensions.ContributedThemeCatalog
import dev.easyide.app.ui.theme.ColorToken
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.HighContrastDarkPalette
import dev.easyide.app.ui.theme.HighContrastLightPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.app.ui.theme.TokenColorRule
import dev.easyide.app.ui.theme.toTokens
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.contrib.ThemeContribution
import dev.easyide.extensions.contrib.UiTheme
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorThemesTest {

    private val root = "/host/ext/"
    private val files = mutableMapOf<String, String>()
    private val warnings = mutableListOf<String>()
    private val read: (String) -> String? = { files[it] }

    private fun file(path: String) = PackageFile(path, root + path)
    private fun put(path: String, text: String) { files[root + path] = text }
    private fun load(path: String) = ColorThemeFile.load(file(path), read) { warnings += it }

    private fun theme(label: String, path: String, ui: UiTheme = UiTheme.DARK, id: String? = null, ext: String = "acme.pack") =
        Owned(Owner.Ext(ExtensionId.parse(ext)!!), ContributionRef(ContributionRef.Kind.THEME, null, label), "/contributes/themes/0",
            ThemeContribution(id, label, ui, file(path)))

    // --- reading theme files ---------------------------------------------------

    @Test fun `colour sections are read and unknown keys ignored`() {
        put("themes/a.json", """
            // JSONC: comments and trailing commas, as VS Code writes them
            {
              "name": "A", "type": "dark", "semanticHighlighting": true, "somethingElse": [1, 2],
              "colors": { "editor.background": "#101010", "not.a.key": "#ffffff", "bad": 3, },
              "tokenColors": [
                { "settings": { "foreground": "#eeeeee" } },
                { "scope": "keyword, storage", "settings": { "foreground": "#ff0000", "fontStyle": "bold" } },
                { "scope": ["string", "comment"], "settings": { "foreground": "#00ff00" } },
                { "scope": "no.settings" },
                "not a rule",
              ],
              "semanticTokenColors": { "parameter": "#123456", "property": { "foreground": "#654321", "bold": true }, "x": { "italic": true } },
            }
        """.trimIndent())
        val t = load("themes/a.json")!!
        assertEquals(mapOf("editor.background" to "#101010", "not.a.key" to "#ffffff"), t.colors)
        assertEquals(
            listOf(
                TokenColorRule(emptyList(), "#eeeeee"),
                TokenColorRule(listOf("keyword, storage"), "#ff0000"),
                TokenColorRule(listOf("string", "comment"), "#00ff00"),
            ),
            t.tokenColors,
        )
        assertEquals(mapOf("parameter" to "#123456", "property" to "#654321"), t.semanticTokenColors)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test fun `an include is read first and the including file wins`() {
        put("themes/base/common.json", """{ "colors": { "editor.background": "#111111", "editor.foreground": "#222222" },
            "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#333333" } } ],
            "semanticTokenColors": { "parameter": "#444444" } }""")
        put("themes/dark.json", """{ "include": "./base/common.json", "colors": { "editor.background": "#aaaaaa" },
            "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#bbbbbb" } } ] }""")
        val t = load("themes/dark.json")!!
        assertEquals(mapOf("editor.background" to "#aaaaaa", "editor.foreground" to "#222222"), t.colors)
        assertEquals(listOf("#333333", "#bbbbbb"), t.tokenColors.map { it.foreground })
        assertEquals(mapOf("parameter" to "#444444"), t.semanticTokenColors)

        // The later (including) rule wins the TextMate tie once mapped.
        val tokens = ContributedThemes.resolve(theme("D", "themes/dark.json").value, read, { warnings += it })!!
        assertEquals(Color(0xFFBBBBBB), tokens.syntax[SyntaxRole.KEYWORD])
        assertTrue(warnings.toString(), warnings.isEmpty())
    }

    @Test fun `includes may climb within the package and chain`() {
        put("shared/root.json", """{ "colors": { "focusBorder": "#010101" } }""")
        put("themes/mid.json", """{ "include": "../shared/root.json", "colors": { "foreground": "#020202" } }""")
        put("themes/top.json", """{ "include": "mid.json" }""")
        assertEquals(mapOf("focusBorder" to "#010101", "foreground" to "#020202"), load("themes/top.json")!!.colors)
    }

    @Test fun `a broken include warns and the file's own colours still apply`() {
        put("themes/out.json", """{ "include": "../../escape.json", "colors": { "foreground": "#020202" } }""")
        put("themes/missing.json", """{ "include": "nope.json", "colors": { "foreground": "#030303" } }""")
        put("themes/self.json", """{ "include": "./self.json", "colors": { "foreground": "#040404" } }""")
        assertEquals(mapOf("foreground" to "#020202"), load("themes/out.json")!!.colors)
        assertEquals(mapOf("foreground" to "#030303"), load("themes/missing.json")!!.colors)
        assertEquals(mapOf("foreground" to "#040404"), load("themes/self.json")!!.colors)
        assertEquals(3, warnings.size)
        assertTrue(warnings[0], "outside the package" in warnings[0])
        assertTrue(warnings[2], "cycle" in warnings[2])
    }

    @Test fun `an include cycle stops instead of looping`() {
        put("a.json", """{ "include": "b.json", "colors": { "foreground": "#0a0a0a" } }""")
        put("b.json", """{ "include": "a.json", "colors": { "editor.background": "#0b0b0b" } }""")
        assertEquals(mapOf("editor.background" to "#0b0b0b", "foreground" to "#0a0a0a"), load("a.json")!!.colors)
        assertTrue(warnings.single(), "cycle" in warnings.single())
    }

    @Test fun `an unusable file is null with a reason`() {
        put("broken.json", """{ "colors": { """)
        put("array.json", "[]")
        assertNull(load("broken.json"))
        assertNull(load("array.json"))
        assertNull(load("absent.json"))
        assertEquals(3, warnings.size)
        assertTrue(warnings[0], "line" in warnings[0])
    }

    @Test fun `a tmTheme tokenColors path warns and keeps the colours`() {
        put("t.json", """{ "tokenColors": "./syntax.tmTheme", "colors": { "foreground": "#050505" } }""")
        val t = load("t.json")!!
        assertEquals(emptyList<TokenColorRule>(), t.tokenColors)
        assertEquals(mapOf("foreground" to "#050505"), t.colors)
        assertTrue(warnings.single(), "tmTheme" in warnings.single())
    }

    @Test fun `relative paths resolve against the including file's directory`() {
        assertEquals("themes/b.json", ColorThemeFile.resolveRelative("themes/a.json", "./b.json"))
        assertEquals("common/b.json", ColorThemeFile.resolveRelative("themes/a.json", "../common/b.json"))
        assertEquals("b.json", ColorThemeFile.resolveRelative("a.json", "b.json"))
        assertNull(ColorThemeFile.resolveRelative("themes/a.json", "../../b.json"))
        assertNull(ColorThemeFile.resolveRelative("themes/a.json", "/etc/passwd"))
        assertNull(ColorThemeFile.resolveRelative("themes/a.json", "..\\b.json"))
    }

    // --- base palette and resolution --------------------------------------------

    @Test fun `uiTheme picks the base palette for tokens the theme leaves unset`() {
        put("empty.json", "{}")
        val expected = mapOf(
            UiTheme.DARK to GraphiteDarkPalette, UiTheme.LIGHT to PaperLightPalette,
            UiTheme.HIGH_CONTRAST_DARK to HighContrastDarkPalette, UiTheme.HIGH_CONTRAST_LIGHT to HighContrastLightPalette,
        )
        expected.forEach { (ui, palette) ->
            assertEquals(ui.wire, palette.toTokens(), ContributedThemes.resolve(theme("E", "empty.json", ui).value, read, { warnings += it }))
        }
    }

    @Test fun `resolution maps colours, reports bad values and unmapped keys, and fails soft`() {
        put("t.json", """{ "colors": { "editor.background": "#fafafa", "editor.foreground": "red", "a.b": "#000", "c.d": "#000" } }""")
        val infos = mutableListOf<String>()
        val tokens = ContributedThemes.resolve(theme("T", "t.json", UiTheme.LIGHT).value, read, { warnings += it }, { infos += it })!!
        assertEquals(Color(0xFFFAFAFA), tokens[ColorToken.EDITOR_BACKGROUND])
        assertEquals(PaperLightPalette.toTokens()[ColorToken.PANEL], tokens[ColorToken.PANEL])
        assertTrue(warnings.single(), "colors:editor.foreground" in warnings.single())
        assertTrue(infos.single(), "2 colour keys" in infos.single())
        assertNull(ContributedThemes.resolve(theme("X", "missing.json").value, read, { warnings += it }))
    }

    @Test fun `selection matches a label or an extension-qualified id, else the built-in palette`() {
        val themes = listOf(theme("Night", "n.json", id = "night"), theme("Day", "d.json", ext = "other.pack"))
        assertEquals("Night", ContributedThemes.find("Night", themes)?.value?.label)
        assertEquals("Night", ContributedThemes.find("acme.pack/night", themes)?.value?.label)
        assertEquals("acme.pack/night", ContributedThemes.refOf(themes[0]))
        assertNull(ContributedThemes.refOf(themes[1]))
        assertNull(ContributedThemes.find("", themes))
        assertNull(ContributedThemes.find("Dusk", themes))
        assertNull(ContributedThemes.find("Night", emptyList()))
    }

    // --- the catalog ------------------------------------------------------------

    @Test fun `the active theme follows the selection and falls back when its extension goes away`() = runBlocking {
        put("n.json", """{ "colors": { "editor.background": "#0c0c0c" } }""")
        val log = mutableListOf<LogEntry>()
        var reads = 0
        val catalog = ContributedThemeCatalog({ log += it }, Dispatchers.Unconfined) { reads++; files[it] }
        val night = theme("Night", "n.json")
        catalog.update(listOf(night), emptyList())
        val selection = MutableStateFlow("Night")

        assertEquals(Color(0xFF0C0C0C), catalog.active(selection).first()!![ColorToken.EDITOR_BACKGROUND])
        assertEquals(Color(0xFF0C0C0C), catalog.tokensFor(night)!![ColorToken.EDITOR_BACKGROUND])
        assertEquals("loaded once per file version", 1, reads)

        catalog.update(emptyList(), emptyList()) // disabled or uninstalled
        assertNull(catalog.active(selection).first())
        selection.value = ""
        catalog.update(listOf(night), emptyList())
        assertNull(catalog.active(selection).first())
        assertTrue(log.isEmpty())
    }

    @Test fun `a broken theme is logged once and applies the built-in palette`() = runBlocking {
        put("bad.json", "not json")
        val log = mutableListOf<LogEntry>()
        val catalog = ContributedThemeCatalog({ log += it }, Dispatchers.Unconfined, read)
        val bad = theme("Bad", "bad.json")
        catalog.update(listOf(bad), emptyList())
        assertNull(catalog.active(MutableStateFlow("Bad")).first())
        assertNull(catalog.tokensFor(bad))
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), log.map { it.level })
        assertNotNull(log.first().extensionId)
    }
}
