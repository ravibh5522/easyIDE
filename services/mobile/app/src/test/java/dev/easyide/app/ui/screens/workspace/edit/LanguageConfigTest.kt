package dev.easyide.app.ui.screens.workspace.edit

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled patterns java.util.regex rejects verbatim (found by compiling all 111). */
class LanguageConfigTest {

    @Test fun `literal braces that are not quantifiers compile`() {
        val go = compileJsRegex(
            "^.*(\\bcase\\b.*:|\\bdefault\\b:|(\\b(func|if|else|switch|select|for|struct)\\b.*)?{[^}\"'`]*|\\([^)\"'`]*)$", "",
        )
        assertNotNull(go)
        assertTrue(go!!.containsMatchIn("func main() {"))
        assertNotNull(compileJsRegex("\\\\begin{(?!document)([^}]*)}(?!.*\\\\end{\\1})", ""))
        assertNotNull(compileJsRegex("({+(?=((\\\\.|[^\"\\\\])*\"(\\\\.|[^\"\\\\])*\")*[^\"}]*)$)", ""))
    }

    @Test fun `real quantifiers are kept`() {
        val r = compileJsRegex("^a{2,3}$", "")!!
        assertTrue(r.matches("aaa"))
        assertTrue(!r.matches("a"))
    }

    @Test fun `unicode property names are mapped`() {
        val word = compileJsRegex("(\\p{Alphabetic}|\\p{Number}|\\p{Nonspacing_Mark}){1,}", "u")!!
        assertTrue(word.matches("abc123"))
    }

    @Test fun `ignore-case flag is honoured`() {
        assertTrue(compileJsRegex("^end$", "i")!!.matches("END"))
    }
}
