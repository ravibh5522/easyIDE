package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetsTest {

    @Test fun `first tab stop placeholder is selected`() {
        val e = SnippetBody.expand("def \${1:name}(\${2:args}):\n\t\$0")
        assertEquals("def name(args):\n\t", e.text)
        assertEquals(4, e.selectionStart)
        assertEquals(8, e.selectionEnd)
    }

    @Test fun `caret goes to $0 without tab stops, else to the end`() {
        assertEquals(ExpandedSnippet("()", 1, 1), SnippetBody.expand("(\$0)"))
        assertEquals(ExpandedSnippet("abc", 3, 3), SnippetBody.expand("abc"))
    }

    @Test fun `choices, nested placeholders, variables and escapes`() {
        assertEquals("r", SnippetBody.expand("\${1|r,w|}").text)
        assertEquals("a b c", SnippetBody.expand("\${1:a \${2:b} c}").text)
        val vars = mapOf("TM_FILENAME" to "x.py")
        assertEquals("x.py and UNKNOWN and dflt", SnippetBody.expand("\$TM_FILENAME and \${UNKNOWN} and \${NOPE:dflt}") { vars[it] }.text)
        assertEquals("\$1 {}", SnippetBody.expand("\\\$1 {\\}").text)
        assertEquals("\${", SnippetBody.expand("\${").text)
    }

    @Test fun `continuation lines get the insertion indent`() {
        val e = SnippetBody.expand("if x:\n\t\${1:pass}", indent = "    ")
        assertEquals("if x:\n    \tpass", e.text)
        assertEquals(11, e.selectionStart)
    }

    @Test fun `snippet files accept string or array bodies and scopes`() {
        val text = """
            // comment: VS Code snippet files are JSONC
            { "main": { "prefix": ["ifmain"], "body": ["a", "b"], "description": "d" },
              "one": { "prefix": "x", "body": "y", "scope": "python, go" },
              "bad": { "prefix": "z" } }
        """
        val global = SnippetFile.parse(text, null, Owner.BuiltIn)!!
        assertEquals(listOf("main", "one"), global.map { it.name })
        assertEquals("a\nb", global[0].body)
        assertEquals(setOf("python", "go"), global[1].languages)
        assertNull(SnippetFile.parse("[]", null, Owner.BuiltIn))
        val catalog = SnippetCatalog(global + SnippetFile.parse(text, "go", Owner.BuiltIn)!!)
        assertEquals(4, catalog.forLanguage("go").size)
        assertEquals(1, catalog.forLanguage("rust").size)
        assertEquals("y", catalog.named("one", "go")!!.body)
        assertEquals(null, catalog.named("one", "rust"))
    }
}
