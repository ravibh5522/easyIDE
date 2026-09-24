package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge-order table of customization.md 8.3. */
class ThemeCustomizerTest {

    private val base = GraphiteDarkPalette.toTokens()

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun apply(colors: String = "{}", tokens: String = "{}", semantic: String = "{}", label: String? = "Ember Night", on: ThemeTokens = base) =
        ThemeCustomizer.apply(on, ColorCustomizations(obj(colors), obj(tokens), obj(semantic)), label)

    @Test
    fun noCustomizationsIsTheSameTheme() {
        assertSame(base, apply().tokens)
    }

    @Test
    fun workbenchColoursTopLevelThenActiveThemeBlock() {
        val r = apply(
            colors = """{"editor.background": "#101010", "statusBar.background": "#202020",
                        "[Ember Night]": {"statusBar.background": "#303030"},
                        "[Other]": {"editor.background": "#FFFFFF"}}""",
        )
        assertEquals(Color(0xFF101010), r.tokens[ColorToken.EDITOR_BACKGROUND])
        assertEquals(Color(0xFF303030), r.tokens[ColorToken.STATUS_BAR])
        assertEquals(base[ColorToken.PANEL], r.tokens[ColorToken.PANEL])
        // Without the label only the top level applies.
        assertEquals(Color(0xFF202020), apply(colors = """{"statusBar.background": "#202020", "[Ember Night]": {"statusBar.background": "#303030"}}""", label = null).tokens[ColorToken.STATUS_BAR])
    }

    @Test
    fun invalidColoursAreSkippedAndListed() {
        val r = apply(colors = """{"editor.background": "red", "editor.foreground": "#12"}""")
        assertEquals(base[ColorToken.EDITOR_BACKGROUND], r.tokens[ColorToken.EDITOR_BACKGROUND])
        assertEquals(listOf("workbench.colorCustomizations:editor.background", "workbench.colorCustomizations:editor.foreground"), r.invalidEntries.sorted())
    }

    @Test
    fun syntaxOrderIsTextMateRulesThenRolesThenThemeBlock() {
        val r = apply(
            tokens = """{
              "textMateRules": [
                {"scope": "keyword", "settings": {"foreground": "#111111", "fontStyle": "bold"}},
                {"scope": ["comment", "string"], "settings": {"foreground": "#222222"}},
                {"scope": "comment", "settings": {"fontStyle": "italic"}}
              ],
              "roles": {"keyword": "#333333"},
              "strings": "#444444",
              "[Ember Night]": {"roles": {"string": {"foreground": "#555555", "fontStyle": "underline"}}}
            }""",
        )
        val syntax = r.tokens.syntax
        assertEquals(Color(0xFF333333), syntax.keyword)
        assertEquals(true, syntax.styles[SyntaxRole.KEYWORD]?.bold)
        assertEquals(Color(0xFF222222), syntax.comment)
        assertEquals(true, syntax.styles[SyntaxRole.COMMENT]?.italic)
        assertEquals(Color(0xFF555555), syntax.string)
        assertEquals(true, syntax.styles[SyntaxRole.STRING]?.underline)
        assertEquals(base.syntax.number, syntax.number)
        assertTrue(r.invalidEntries.isEmpty())
    }

    @Test
    fun longestTextMateSelectorWinsLikeThemeTokenColors() {
        val r = apply(tokens = """{"textMateRules": [
            {"scope": "keyword.operator", "settings": {"foreground": "#AA0000"}},
            {"scope": "keyword", "settings": {"foreground": "#00AA00"}}]}""")
        assertEquals(Color(0xFFAA0000), r.tokens.syntax.operator)
        assertEquals(Color(0xFF00AA00), r.tokens.syntax.keyword)
    }

    @Test
    fun semanticRulesLayerOverTheThemeAndEnabledOverridesItsFlag() {
        val themed = VsCodeThemeMapper.map(
            VsCodeColorTheme(semanticTokenColors = mapOf("function" to "#010101", "variable" to "#020202"), semanticHighlighting = false),
            base,
        ).tokens
        assertFalse(themed.semantic.highlighting!!)
        val r = apply(
            semantic = """{"enabled": true,
                "rules": {"function": {"foreground": "#030303", "bold": true}, "*.deprecated": {"underline": true}},
                "[Ember Night]": {"rules": {"variable": "#040404"}}}""",
            on = themed,
        )
        val sem = r.tokens.semantic
        assertEquals(true, sem.highlighting)
        assertEquals(TokenStyle(Color(0xFF030303), bold = true), sem.rules["function"])
        assertEquals(TokenStyle(Color(0xFF040404)), sem.rules["variable"])
        assertEquals(TokenStyle(underline = true), sem.rules["*.deprecated"])
        assertEquals("customized entries come after the theme's", listOf("function", "*.deprecated", "variable"), sem.rules.keys.toList())
    }

    @Test
    fun customizedThemeReachesEditorColours() {
        val r = apply(semantic = """{"rules": {"class": "#0A0A0A"}}""", tokens = """{"roles": {"comment": {"fontStyle": "italic"}}}""")
        val editor = r.tokens.toEditorColors()
        assertEquals(TokenStyle(Color(0xFF0A0A0A)), editor.semantic.rules["class"])
        assertEquals(true, editor.syntax.styles[SyntaxRole.COMMENT]?.italic)
        assertEquals(base.syntax.comment, editor.syntax.comment)
    }
}
