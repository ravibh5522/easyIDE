package dev.easyide.app.ui.screens.workspace.edit

import dev.easyide.app.ui.screens.workspace.edit.Fixtures.markdown
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.python
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.render
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.state
import dev.easyide.app.ui.screens.workspace.edit.Fixtures.typescript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditCommandsTest {

    private fun toggle(marked: String, config: LanguageConfig) =
        render(EditCommands.toggleComment(state(marked), config))

    @Test fun `toggle comments the caret line at its indent`() {
        assertEquals("    // x|", toggle("    x|", typescript))
        assertEquals("# |x", toggle("|x", python))
    }

    @Test fun `toggle uncomments when every line is commented`() {
        assertEquals("    x|", toggle("    // x|", typescript))
        assertEquals("[a\nb]", toggle("[# a\n#b]", python))
    }

    @Test fun `multi-line toggle aligns markers and skips blank lines`() {
        assertEquals("[  // a\n\n  //   b]", toggle("[  a\n\n    b]", typescript))
    }

    @Test fun `mixed block is commented, not uncommented`() {
        assertEquals("[// // a\n// b]", toggle("[// a\nb]", typescript))
    }

    @Test fun `selection ending at column zero leaves the next line alone`() {
        assertEquals("[# a\n]b", toggle("[a\n]b", python))
    }

    @Test fun `languages with only block comments wrap the lines`() {
        assertEquals("<!-- text -->|", toggle("text|", markdown))
        assertEquals("text|", toggle("<!-- text -->|", markdown))
    }

    @Test fun `no comment tokens is a no-op`() {
        val s = state("x|")
        assertEquals(s, EditCommands.toggleComment(s, LanguageConfig.GENERIC))
    }

    @Test fun `matching bracket found in both directions`() {
        val text = "f(a[1], (b))"
        assertEquals(1 to 11, EditCommands.matchingBracket(text, 2, typescript.brackets))
        assertEquals(11 to 1, EditCommands.matchingBracket(text, 12, typescript.brackets))
        assertEquals(3 to 5, EditCommands.matchingBracket(text, 3, typescript.brackets))
    }

    @Test fun `unmatched bracket or none at caret gives null`() {
        assertNull(EditCommands.matchingBracket("f(a", 2, typescript.brackets))
        assertNull(EditCommands.matchingBracket("abc", 1, typescript.brackets))
    }
}
