package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VsCodeThemeMapperTest {

    private val base = GraphiteDarkPalette.toTokens()

    private fun map(
        colors: Map<String, String> = emptyMap(),
        tokenColors: List<TokenColorRule> = emptyList(),
        semantic: Map<String, String> = emptyMap(),
    ) = VsCodeThemeMapper.map(VsCodeColorTheme(colors, tokenColors, semantic), base)

    private fun rule(vararg scopes: String, fg: String?) = TokenColorRule(scopes.toList(), fg)

    // --- colours ------------------------------------------------------------

    @Test fun `an empty theme is the base unchanged`() {
        val mapped = map()
        // Colours and roles unchanged; a theme that does not say `semanticHighlighting` turns
        // configuredByTheme semantic colouring off (customization.md 8.3).
        assertEquals(base.withOverrides(emptyMap(), semantic = SemanticTokenColors(emptyMap(), highlighting = false)), mapped.tokens)
        assertTrue(mapped.unmappedKeys.isEmpty() && mapped.invalidEntries.isEmpty() && mapped.semanticColors.isEmpty())
    }

    @Test fun `mapped keys land on their tokens and the rest keep the base`() {
        val mapped = map(colors = mapOf("editor.background" to "#101010", "statusBar.foreground" to "#abcdef"))
        assertEquals(Color(0xFF101010), mapped.tokens[ColorToken.EDITOR_BACKGROUND])
        assertEquals(Color(0xFFABCDEF), mapped.tokens[ColorToken.STATUS_BAR_FOREGROUND])
        assertEquals(base[ColorToken.PANEL], mapped.tokens[ColorToken.PANEL])
    }

    @Test fun `every mapped VS Code key reaches its token`() {
        ThemeColorMap.BY_VSCODE_KEY.forEach { (key, token) ->
            // Later table entries for the same token win; only check keys that are the winner.
            val winner = ThemeColorMap.BY_VSCODE_KEY.entries.last { it.value == token }.key
            if (key != winner) return@forEach
            assertEquals(key, Color(0xFF123456), map(colors = mapOf(key to "#123456")).tokens[token])
        }
    }

    @Test fun `the later table entry wins when two keys feed one token`() {
        val colors = linkedMapOf("sideBar.background" to "#222222", "panel.background" to "#111111")
        assertEquals(Color(0xFF222222), map(colors = colors).tokens[ColorToken.PANEL])
    }

    @Test fun `unset tokens follow the colour they derive from`() {
        val mapped = map(colors = mapOf("editor.background" to "#202020", "foreground" to "#eeeeee"))
        assertEquals(Color(0xFF202020), mapped.tokens[ColorToken.GUTTER_BACKGROUND])
        assertEquals(Color(0xFF202020), mapped.tokens[ColorToken.TAB_ACTIVE])
        assertEquals(Color(0xFF202020), mapped.tokens[ColorToken.TERMINAL_BACKGROUND])
        // foreground -> editor.foreground -> terminal foreground, chained in table order.
        assertEquals(Color(0xFFEEEEEE), mapped.tokens[ColorToken.EDITOR_FOREGROUND])
        assertEquals(Color(0xFFEEEEEE), mapped.tokens[ColorToken.TERMINAL_FOREGROUND])
    }

    @Test fun `an explicit colour beats a derived one`() {
        val mapped = map(colors = mapOf("editor.background" to "#202020", "editorGutter.background" to "#303030"))
        assertEquals(Color(0xFF303030), mapped.tokens[ColorToken.GUTTER_BACKGROUND])
    }

    @Test fun `unknown keys are reported sorted and invalid values skipped`() {
        val mapped = map(colors = mapOf("zeta.unknown" to "#fff", "alpha.unknown" to "#fff", "editor.background" to "blue"))
        assertEquals(listOf("alpha.unknown", "zeta.unknown"), mapped.unmappedKeys)
        assertEquals(listOf("colors:editor.background"), mapped.invalidEntries)
        assertEquals(base[ColorToken.EDITOR_BACKGROUND], mapped.tokens[ColorToken.EDITOR_BACKGROUND])
    }

    // --- tokenColors ----------------------------------------------------------

    @Test fun `a selector colours every role whose prefix it covers`() {
        val syntax = map(tokenColors = listOf(rule("keyword", fg = "#ff0000"))).tokens.syntax
        assertEquals(Color(0xFFFF0000), syntax.keyword)
        // keyword.operator starts with "keyword.", so OPERATOR follows too.
        assertEquals(Color(0xFFFF0000), syntax.operator)
        assertEquals(base.syntax.string, syntax.string)
    }

    @Test fun `the longest matching selector wins`() {
        val syntax = map(
            tokenColors = listOf(
                rule("keyword.operator", fg = "#00ff00"),
                rule("keyword", fg = "#ff0000"),
            ),
        ).tokens.syntax
        assertEquals(Color(0xFF00FF00), syntax.operator)
        assertEquals(Color(0xFFFF0000), syntax.keyword)
    }

    @Test fun `a later rule wins a tie`() {
        val syntax = map(
            tokenColors = listOf(rule("string", fg = "#111111"), rule("string", fg = "#222222")),
        ).tokens.syntax
        assertEquals(Color(0xFF222222), syntax.string)
    }

    @Test fun `a selector more specific than every prefix does not match`() {
        val syntax = map(tokenColors = listOf(rule("string.quoted.double.kotlin", fg = "#123456"))).tokens.syntax
        assertEquals(base.syntax.string, syntax.string)
    }

    @Test fun `comma lists, descendant selectors and exclusions are normalised`() {
        val syntax = map(
            tokenColors = listOf(
                rule("comment, source.python entity.name.function", fg = "#0000ff"),
                rule("string - string.regexp", fg = "#00ffff"),
            ),
        ).tokens.syntax
        assertEquals(Color(0xFF0000FF), syntax.comment)
        assertEquals(Color(0xFF0000FF), syntax.function)
        assertEquals(Color(0xFF00FFFF), syntax.string)
    }

    @Test fun `plain text follows editor foreground unless a scope-less rule sets it`() {
        assertEquals(Color(0xFFEEEEEE), map(colors = mapOf("editor.foreground" to "#eeeeee")).tokens.syntax.plain)
        val both = map(
            colors = mapOf("editor.foreground" to "#eeeeee"),
            tokenColors = listOf(TokenColorRule(emptyList(), "#dddddd")),
        )
        assertEquals(Color(0xFFDDDDDD), both.tokens.syntax.plain)
    }

    @Test fun `rules without a foreground are ignored and bad colours reported by index`() {
        val mapped = map(tokenColors = listOf(rule("keyword", fg = null), rule("string", fg = "#zzz")))
        assertEquals(base.syntax, mapped.tokens.syntax)
        assertEquals(listOf("tokenColors:1"), mapped.invalidEntries)
    }

    // --- semanticTokenColors -------------------------------------------------

    @Test fun `semantic colours are validated and kept by selector`() {
        val mapped = map(semantic = mapOf("function.declaration" to "#aabbcc", "variable.readonly:kotlin" to "nope"))
        assertEquals(mapOf("function.declaration" to Color(0xFFAABBCC)), mapped.semanticColors)
        assertEquals(mapOf("function.declaration" to TokenStyle(Color(0xFFAABBCC))), mapped.tokens.semantic.rules)
        assertEquals(listOf("semanticTokenColors:variable.readonly:kotlin"), mapped.invalidEntries)
    }

    // --- parseHexColor --------------------------------------------------------

    @Test fun `hex colours parse in all four CSS lengths with alpha last`() {
        assertEquals(Color(0xFFAABBCC), parseHexColor("#abc"))
        assertEquals(Color(0xDDAABBCC), parseHexColor("#abcd"))
        assertEquals(Color(0xFF123456), parseHexColor("#123456"))
        assertEquals(Color(0x80123456), parseHexColor("#12345680"))
        assertEquals(Color(0xFF123456), parseHexColor("  #123456 "))
    }

    @Test fun `anything else is not a colour`() {
        listOf("", "#", "123456", "#12345", "#1234567", "#gggggg", "rgb(1,2,3)", "#123456789")
            .forEach { assertNull(it, parseHexColor(it)) }
    }

    @Test fun `withOverrides without changes returns the same instance`() {
        assertSame(base, base.withOverrides(emptyMap()))
    }
}
