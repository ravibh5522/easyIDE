package dev.easyide.app.ui.screens.workspace.edit

import dev.easyide.app.ui.screens.workspace.edit.Fixtures.python
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.render
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.state
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.typescript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TypingRulesTest {

    private val options = TypingOptions()

    /** Types [c] the way the text field reports it: old state, then old + c. */
    private fun type(marked: String, c: Char, config: LanguageConfig = typescript, opts: TypingOptions = options): String {
        val old = state(marked)
        val inserted = old.text.substring(0, old.min) + c + old.text.substring(old.max)
        return render(TypingRules.onChange(old, TextState(inserted, old.min + 1), config, opts))
    }

    private fun backspace(marked: String, config: LanguageConfig = typescript): String {
        val old = state(marked)
        val deleted = old.text.removeRange(old.min - 1, old.min)
        return render(TypingRules.onChange(old, TextState(deleted, old.min - 1), config, options))
    }

    @Test fun `opening bracket inserts its partner with the caret inside`() {
        assertEquals("f(|)", type("f|", '('))
        assertEquals("x = [|]", type("x = |", '['))
    }

    @Test fun `no auto-close before a word character`() {
        assertEquals("(|abc", type("|abc", '('))
    }

    @Test fun `auto-close is allowed before a character from autoCloseBefore`() {
        assertEquals("f((|))", type("f(|)", '('))
    }

    @Test fun `typing a closer next to the caret steps over it`() {
        assertEquals("f()|", type("f(|)", ')'))
    }

    @Test fun `quote inside a string closes it by stepping over`() {
        assertEquals("s = \"abc\"|", type("s = \"abc|\"", '"'))
    }

    @Test fun `quote is not auto-closed inside a string`() {
        assertEquals("s = \"a\"|b\"", type("s = \"a|b\"", '"'))
    }

    @Test fun `apostrophe after a letter is not doubled`() {
        assertEquals("// don'|", type("// don|", '\'', typescript))
        assertEquals("x = don'|", type("x = don|", '\''))
    }

    @Test fun `single quote is not auto-closed in a line comment`() {
        assertEquals("# it '|", type("# it |", '\'', python))
    }

    @Test fun `longest open wins for multi-character pairs`() {
        assertEquals("x = f\"|\"", type("x = f|", '"', python))
        assertEquals("/**| */", type("/*|", '*'))
    }

    @Test fun `typing an opener over a selection wraps it`() {
        assertEquals("f([abc])", type("f[abc]", '('))
    }

    @Test fun `auto-close can be switched off`() {
        assertEquals("f(|", type("f|", '(', opts = options.copy(autoClose = false)))
    }

    @Test fun `backspace inside an empty pair deletes both halves`() {
        assertEquals("f|", backspace("f(|)"))
        assertEquals("f(|x", backspace("f((|x"))
    }

    @Test fun `enter keeps the indentation of the line`() {
        assertEquals("    x = 1\n    |", type("    x = 1|", '\n', python))
    }

    @Test fun `enter after a python block opener indents one level`() {
        assertEquals("def f():\n    |", type("def f():|", '\n', python))
        assertEquals("    if x:\n        |", type("    if x:|", '\n', python))
    }

    @Test fun `enter between braces opens an indented line and pushes the closer down`() {
        assertEquals("fn() {\n    |\n}", type("fn() {|}", '\n'))
    }

    @Test fun `enter between brackets without a rule uses the bracket fallback`() {
        assertEquals("f(\n    |\n)", type("f(|)", '\n'))
    }

    @Test fun `enter inside a doc comment continues it`() {
        assertEquals("/**\n * |\n */", type("/**| */", '\n'))
    }

    @Test fun `tab-indented lines keep tabs`() {
        assertEquals("\tif (x) {\n\t\t|\n\t}", type("\tif (x) {|}", '\n'))
    }

    @Test fun `enter with auto-indent off inserts a bare newline`() {
        assertEquals("def f():\n|", type("def f():|", '\n', python, options.copy(autoIndent = false)))
    }

    @Test fun `closing bracket on a whitespace-only line outdents it`() {
        assertEquals("if (x) {\n    y\n}|", type("if (x) {\n    y\n    |", '}'))
    }

    @Test fun `a paste passes through untouched`() {
        val old = state("f|")
        val pasted = TextState("f(a, b)", 7)
        assertSame(pasted, TypingRules.onChange(old, pasted, typescript, options))
    }

    @Test fun `generic config still closes brackets`() {
        assertEquals("[|]", type("|", '[', LanguageConfig.GENERIC))
    }
}
