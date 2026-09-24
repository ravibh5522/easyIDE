package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.screens.workspace.syntax.SemanticKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticStylerTest {

    private val syntax = themeTokensFor(ThemeMode.DARK, systemInDark = true).syntax
    private val a = Color(0xFF0000AA)
    private val b = Color(0xFF00BB00)
    private val c = Color(0xFFCC0000)

    private fun key(type: String, vararg mods: String) = SemanticKey(type, mods.toSet())

    @Test
    fun selectorsParse() {
        assertEquals(SemanticSelector("variable", setOf("readonly", "static"), "python"), SemanticSelector.parse("variable.readonly.static:python"))
        assertEquals(SemanticSelector("*", setOf("deprecated"), null), SemanticSelector.parse("*.deprecated"))
        assertNull(SemanticSelector.parse("bad selector"))
        assertNull(SemanticSelector.parse("variable."))
    }

    @Test
    fun mostSpecificSelectorWinsLaterBreaksTies() {
        val rules = mapOf(
            "variable" to TokenStyle(a),
            "*.readonly" to TokenStyle(b),
            "variable.readonly" to TokenStyle(c),
        )
        val styler = SemanticStyler(syntax, SemanticTokenColors(rules), null)
        assertEquals(a, styler.resolve(key("variable"))!!.color)
        assertEquals(c, styler.resolve(key("variable", "readonly"))!!.color)
        assertEquals(b, styler.resolve(key("parameter", "readonly"))!!.color)
        val tie = SemanticStyler(syntax, SemanticTokenColors(linkedMapOf("variable" to TokenStyle(a), "*.readonly" to TokenStyle(b))), null)
        assertEquals("later rule wins a tie", b, tie.resolve(key("variable", "readonly"))!!.color)
    }

    @Test
    fun languageSelectorsApplyOnlyToTheirLanguage() {
        val rules = mapOf("function" to TokenStyle(a), "function:python" to TokenStyle(b))
        assertEquals(b, SemanticStyler(syntax, SemanticTokenColors(rules), "python").resolve(key("function"))!!.color)
        assertEquals(a, SemanticStyler(syntax, SemanticTokenColors(rules), "go").resolve(key("function"))!!.color)
    }

    @Test
    fun withoutARuleTheRoleColourApplies() {
        val styler = SemanticStyler(syntax, SemanticTokenColors.BUILT_IN, null)
        assertEquals(syntax[SyntaxRole.TYPE], styler.resolve(key("class"))!!.color)
        assertEquals(syntax[SyntaxRole.CONSTANT], styler.resolve(key("variable", "readonly"))!!.color)
    }

    @Test
    fun styleOnlyRuleKeepsTheRoleColour() {
        val styler = SemanticStyler(syntax, SemanticTokenColors(mapOf("*.deprecated" to TokenStyle(underline = true))), null)
        val style = styler.resolve(key("function", "deprecated"))!!
        assertEquals(syntax[SyntaxRole.FUNCTION], style.color)
        assertEquals(true, style.underline)
    }

    @Test
    fun fontStyleParses() {
        assertEquals(TokenStyle(a, bold = true, italic = true, underline = false), TokenStyle.fromFontStyle("italic bold", a))
        assertEquals(TokenStyle(null, bold = false, italic = false, underline = false), TokenStyle.fromFontStyle(""))
        assertEquals(TokenStyle(a), TokenStyle.fromFontStyle(null, a))
    }
}
