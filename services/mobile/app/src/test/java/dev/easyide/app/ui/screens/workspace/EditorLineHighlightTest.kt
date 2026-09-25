package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.ui.screens.workspace.syntax.ScopeRules
import dev.easyide.app.ui.theme.SyntaxRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLineHighlightTest {

    @Test fun `scope prefixes per role come from the one scope table`() {
        assertEquals(listOf("keyword.operator"), ScopeRules.prefixesFor(SyntaxRole.OPERATOR))
        assertTrue("storage.type" in ScopeRules.prefixesFor(SyntaxRole.TYPE))
        assertTrue(ScopeRules.prefixesFor(SyntaxRole.PLAIN).isEmpty())
    }
}
