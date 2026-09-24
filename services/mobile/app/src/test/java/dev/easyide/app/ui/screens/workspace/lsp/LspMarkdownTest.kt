package dev.easyide.app.ui.screens.workspace.lsp

import org.junit.Assert.assertEquals
import org.junit.Test

class LspMarkdownTest {
    @Test
    fun unescapesProseButNotCode() {
        val md = "my\\_func\\(x\\)\n```python\nprint(\"a\\_b\")\n```\n\\*done\\*"
        assertEquals("my_func(x)\n```python\nprint(\"a\\_b\")\n```\n\\*done\\*", LspMarkdown.prepare(md))
    }
}
