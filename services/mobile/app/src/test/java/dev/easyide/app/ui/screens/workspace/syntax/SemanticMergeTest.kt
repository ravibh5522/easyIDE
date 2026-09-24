package dev.easyide.app.ui.screens.workspace.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.easyide.app.ui.theme.SemanticStyler
import dev.easyide.app.ui.theme.SemanticTokenColors
import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.app.ui.theme.TokenStyle
import dev.easyide.app.ui.theme.themeTokensFor
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.lsp.protocol.SemanticToken
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.registry.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The renderer merge of lsp-features.md 4.13 against a real TextMate grammar: semantic spans
 * are added after the grammar's spans of the same line, so they win where both colour a range
 * (pins open issue O2 at the AnnotatedString level), and TextMate colours everything else.
 */
class SemanticMergeTest {

    private val grammar = Registry(
        grammarSource = { scope ->
            if (scope != "source.t") null
            else GrammarReader.readGrammar(
                """{"scopeName":"source.t","patterns":[
                   {"match":"\\b(def|return)\\b","name":"keyword.control.t"},
                   {"match":"\\b[a-z]+\\b","name":"variable.other.t"}]}""",
            )
        },
    ).loadGrammar("source.t")!!

    private val syntax = themeTokensFor(ThemeMode.DARK, systemInDark = true).syntax
    private val text = "def foo\nreturn bar"

    /** The style that paints [offset] last, i.e. the one the text shows. */
    private fun AnnotatedString.colorAt(offset: Int): Color? =
        spanStyles.lastOrNull { offset >= it.start && offset < it.end }?.item?.color

    private fun annotate(paint: SemanticPaint?): AnnotatedString {
        val doc = DocumentHighlighter(grammar)
        doc.setContent(text)
        return doc.annotate(text, syntax, 0, 1, semantic = paint)
    }

    @Test
    fun semanticColourWinsWhereItOverlapsAndTextMateColoursTheRest() {
        val overlay = SemanticOverlay.build(text, listOf(SemanticToken(0, 4, 3, "function", setOf("declaration"))))
        val styled = annotate(SemanticPaint.of(overlay, text, colors(), null))
        assertEquals(syntax[SyntaxRole.VARIABLE], annotate(null).colorAt(4))
        assertEquals(syntax[SyntaxRole.FUNCTION], styled.colorAt(4))
        assertEquals(syntax[SyntaxRole.KEYWORD], styled.colorAt(0))
        assertEquals(syntax[SyntaxRole.VARIABLE], styled.colorAt(text.indexOf("bar")))
    }

    @Test
    fun overlayFollowsAnEditAboveIt() {
        val overlay = SemanticOverlay.build(text, listOf(SemanticToken(1, 7, 3, "parameter", emptySet())))
        val edited = "# c\n$text"
        val doc = DocumentHighlighter(grammar)
        doc.setContent(edited)
        val styled = doc.annotate(edited, syntax, 0, 2, semantic = SemanticPaint.of(overlay, edited, colors(), null))
        assertEquals(syntax[SyntaxRole.PARAMETER], styled.colorAt(edited.indexOf("bar")))
    }

    @Test
    fun themeRuleBeatsTheRoleAndFontStyleIsApplied() {
        val red = Color(0xFFFF0000)
        val theme = SemanticTokenColors(mapOf("function.declaration" to TokenStyle(red, bold = true)))
        val overlay = SemanticOverlay.build(text, listOf(SemanticToken(0, 4, 3, "function", setOf("declaration"))))
        val styled = annotate(SemanticPaint.of(overlay, text, colors(theme), null))
        val span = styled.spanStyles.last { 4 >= it.start && 4 < it.end }.item
        assertEquals(red, span.color)
        assertEquals(FontWeight.Bold, span.fontWeight)
    }

    @Test
    fun unknownTokenTypeKeepsTextMate() {
        val styler = SemanticStyler(syntax, SemanticTokenColors.BUILT_IN, null)
        assertNull(styler.styleFor(SemanticKey("somethingNew", emptySet())))
    }

    private fun colors(semantic: SemanticTokenColors = SemanticTokenColors.BUILT_IN) =
        themeTokensFor(ThemeMode.DARK, systemInDark = true).toEditorColors().copy(semantic = semantic)
}
