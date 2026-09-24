package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.easyide.app.ui.screens.workspace.syntax.ScopeRules
import dev.easyide.app.ui.theme.SyntaxRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLineHighlightTest {

    private val numbers = (1..12).joinToString("\n")

    @Test fun `the active line's number is styled and nothing else`() {
        val text = gutterNumbers(numbers, activeLine = 9, activeColor = Color.Red)
        val span = text.spanStyles.single()
        assertEquals("10", numbers.substring(span.start, span.end))
        assertEquals(Color.Red, span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(numbers, text.text)
    }

    @Test fun `first and last lines are styled to their bounds`() {
        val first = gutterNumbers(numbers, 0, Color.Red).spanStyles.single()
        assertEquals("1", numbers.substring(first.start, first.end))
        val last = gutterNumbers(numbers, 11, Color.Red).spanStyles.single()
        assertEquals("12", numbers.substring(last.start, last.end))
    }

    @Test fun `a caret line past the end styles nothing`() {
        assertTrue(gutterNumbers(numbers, 40, Color.Red).spanStyles.isEmpty())
    }

    @Test fun `scope prefixes per role come from the one scope table`() {
        assertEquals(listOf("keyword.operator"), ScopeRules.prefixesFor(SyntaxRole.OPERATOR))
        assertTrue("storage.type" in ScopeRules.prefixesFor(SyntaxRole.TYPE))
        assertTrue(ScopeRules.prefixesFor(SyntaxRole.PLAIN).isEmpty())
    }
}
