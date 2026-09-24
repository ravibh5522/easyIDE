package dev.easyide.extensions.whenclause

import dev.easyide.extensions.ExtensionPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WhenClauseTest {
    private fun parse(s: String): WhenExpr = when (val r = WhenParser.parse(s)) {
        is WhenParseResult.Ok -> r.expr
        is WhenParseResult.Error -> { fail("'$s' failed at ${r.offset}: ${r.message}"); error("unreachable") }
    }

    private fun parseError(s: String): WhenParseResult.Error =
        WhenParser.parse(s) as? WhenParseResult.Error ?: run { fail("'$s' parsed"); error("unreachable") }

    private fun ctx(json: String): ContextLookup {
        val obj = Json.parseToJsonElement(json) as JsonObject
        return ContextLookup { obj[it] }
    }

    private fun eval(s: String, json: String = "{}"): Boolean = WhenEvaluator.evaluate(parse(s), ctx(json))

    @Test fun `identifiers keep colons, dots and dashes`() {
        assertEquals(WhenExpr.Key("lspSupports:python:formatting"), parse("lspSupports:python:formatting"))
        assertEquals(WhenExpr.Key("config.editor.tabSize"), parse("config.editor.tabSize"))
        assertEquals(setOf("a-b.c"), parse("a-b.c").keys)
    }

    @Test fun `precedence - not binds tighter than and, and tighter than or`() {
        val e = parse("!a && b || c")
        assertEquals(WhenExpr.Or(listOf(WhenExpr.And(listOf(WhenExpr.Not(WhenExpr.Key("a")), WhenExpr.Key("b"))), WhenExpr.Key("c"))), e)
        assertTrue(eval("!a && b || c", """{"c":true}"""))
        assertTrue(eval("!a && b || c", """{"b":true}"""))
        assertFalse(eval("!a && b || c", """{"a":true,"b":true}"""))
        assertTrue(eval("a && (b || c)", """{"a":1,"c":1}"""))
        assertFalse(eval("a && (b || c)", """{"c":1}"""))
    }

    @Test fun `not applies to a whole comparison`() {
        assertEquals(WhenExpr.Not(WhenExpr.Compare("a", CompareOp.EQ, "x")), parse("!a == x"))
    }

    @Test fun `truthiness - undefined, null, false, empty and zero are falsy`() {
        listOf("""{}""", """{"k":null}""", """{"k":false}""", """{"k":""}""", """{"k":0}""").forEach { assertFalse(it, eval("k", it)) }
        listOf("""{"k":true}""", """{"k":"x"}""", """{"k":2}""", """{"k":[]}""", """{"k":{}}""").forEach { assertTrue(it, eval("k", it)) }
        assertTrue(eval("true"))
        assertFalse(eval("false"))
    }

    @Test fun `equality compares as strings, unquoted words are strings`() {
        assertTrue(eval("editorLangId == python", """{"editorLangId":"python"}"""))
        assertTrue(eval("editorLangId == 'python'", """{"editorLangId":"python"}"""))
        assertFalse(eval("editorLangId != python", """{"editorLangId":"python"}"""))
        assertTrue(eval("resourceExtname == .py", """{"resourceExtname":".py"}"""))
        assertTrue(eval("flag == true", """{"flag":true}"""))
        assertTrue(eval("n == 4", """{"n":4.0}"""))
        assertTrue(eval("s == 'it\\'s'", """{"s":"it's"}"""))
    }

    @Test fun `undefined keys - equality false, inequality true`() {
        assertFalse(eval("missing == x"))
        assertTrue(eval("missing != x"))
        assertFalse(eval("missing < 3"))
    }

    @Test fun `ordering compares numbers, non-numeric is false`() {
        assertTrue(eval("n < 5", """{"n":4}"""))
        assertTrue(eval("n <= 4", """{"n":4}"""))
        assertTrue(eval("n > 3.5", """{"n":4}"""))
        assertTrue(eval("n >= 4", """{"n":"4"}"""))
        assertFalse(eval("n > x", """{"n":4}"""))
        assertFalse(eval("n > 1", """{"n":"abc"}"""))
        assertTrue(eval("n > -1", """{"n":0}"""))
    }

    @Test fun `regex matches with flags and escaped slash`() {
        assertTrue(eval("resourceFilename =~ /^test_.*\\.py$/", """{"resourceFilename":"test_a.py"}"""))
        assertFalse(eval("f =~ /abc/", """{"f":"ABC"}"""))
        assertTrue(eval("f =~ /abc/i", """{"f":"ABC"}"""))
        assertTrue(eval("p =~ /src\\/main/", """{"p":"a/src/main/b"}"""))
        assertTrue(eval("p =~ /[/]x/", """{"p":"/x"}"""))
        assertFalse(eval("n =~ /1/", """{"n":1}"""))
        assertFalse(eval("missing =~ /x/"))
        assertEquals("u", (parse("f =~ /a/u") as WhenExpr.Matches).flags)
    }

    @Test fun `in and not in with key arrays, objects and quoted lists`() {
        val c = """{"d":"debian","list":["a","debian"],"obj":{"debian":1},"other":"x"}"""
        assertTrue(eval("d in 'debian,ubuntu'", c))
        assertTrue(eval("d in 'ubuntu, debian'", c))
        assertFalse(eval("d in 'alpine'", c))
        assertTrue(eval("d in list", c))
        assertTrue(eval("d in obj", c))
        assertFalse(eval("other in list", c))
        assertTrue(eval("other not in list", c))
        assertFalse(eval("d not in list", c))
        assertFalse(eval("missing in list", c))
        assertFalse(eval("missing not in list", c))
        assertFalse(eval("d in missingContainer", c))
        assertTrue(eval("d not in missingContainer", c))
        assertEquals(setOf("d", "list"), parse("d in list").keys)
    }

    @Test fun `errors report the offending offset`() {
        assertEquals(0, parseError("").offset)
        assertEquals(4, parseError("a &&").offset)
        assertEquals(2, parseError("a & b").offset)
        assertEquals(5, parseError("(a ||").offset)
        assertEquals(2, parseError("a (").offset)
        assertEquals(5, parseError("a == (").offset)
        assertEquals(5, parseError("a =~ x").offset)
        assertEquals(5, parseError("a =~ /x").offset)
        assertEquals(8, parseError("a =~ /x/g").offset)
        assertEquals(5, parseError("a =~ /(/").offset)
        assertEquals(5, parseError("a == 'x").offset)
        assertEquals(6, parseError("a not b").offset)
        assertEquals(5, parseError("true == x").offset)
        assertEquals(5, parseError("a in (b)").offset)
        assertEquals(2, parseError("a # b").offset)
    }

    @Test fun `limits bound hostile clauses`() {
        assertEquals(0, parseError("a".repeat(ExtensionPolicy.MAX_WHEN_LENGTH + 1)).offset)
        val deep = "(".repeat(WhenParser.MAX_DEPTH + 1) + "a" + ")".repeat(WhenParser.MAX_DEPTH + 1)
        assertTrue(parseError(deep).message.contains("nesting"))
        assertTrue(parseError("a =~ /" + "x".repeat(ExtensionPolicy.MAX_PATTERN_LENGTH + 1) + "/").message.contains("longer"))
    }

    @Test fun `normalize round-trips to an equal tree`() {
        listOf(
            "!a && b || c", "a && (b || c)", "!(a && b)", "!!a", "x == 'it\\'s'", "n >= 3", "r =~ /a\\/b/i",
            "d in 'debian,ubuntu'", "d not in list", "resourceExtname == .py", "a == b && c != 'd e'", "true || false",
        ).forEach { src ->
            val e = parse(src)
            assertEquals(src, e, parse(WhenParser.normalize(e)))
        }
        assertEquals("a && (b || c)", WhenParser.normalize(parse("a&&(b||c)")))
        assertEquals("x == '.py'", WhenParser.normalize(parse("x == .py")))
    }

    @Test fun `evaluation never throws on odd values`() {
        val odd: Map<String, JsonElement> = mapOf("k" to Json.parseToJsonElement("[1,{\"a\":null}]"))
        val lookup = ContextLookup { odd[it] }
        listOf("k", "k == x", "k < 1", "k =~ /x/", "k in k", "x in k").forEach { WhenEvaluator.evaluate(parse(it), lookup) }
    }
}
