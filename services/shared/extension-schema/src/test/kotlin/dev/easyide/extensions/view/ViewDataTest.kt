package dev.easyide.extensions.view

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewDataTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun data(w: ViewData.Written) = (w as ViewData.Written.Data).data

    @Test fun `an update merges top-level keys, null removes, and typed input survives`() {
        val before = obj("""{ "query": "web", "containers": [1], "old": true }""")
        val after = data(ViewData.merge(before, obj("""{ "containers": [1, 2], "old": null }""")))
        assertEquals(obj("""{ "query": "web", "containers": [1, 2] }"""), after)
    }

    @Test fun `an update that is not an object or is too large is rejected whole`() {
        assertTrue(ViewData.merge(obj("{}"), Json.parseToJsonElement("[1]")) is ViewData.Written.Rejected)
        val big = obj("""{ "x": "${"a".repeat(ViewLimits.MAX_UPDATE_BYTES)}" }""")
        val before = obj("""{ "keep": 1 }""")
        assertTrue(ViewData.merge(before, big) is ViewData.Written.Rejected)
        assertEquals(1, before.size)
    }

    @Test fun `set creates the path, replacing a non-object on the way`() {
        assertEquals(obj("""{ "a": { "b": { "c": 1 } } }"""), ViewData.set(obj("{}"), "a.b.c", JsonPrimitive(1)))
        assertEquals(obj("""{ "a": { "b": 2 } }"""), ViewData.set(obj("""{ "a": "text" }"""), "a.b", JsonPrimitive(2)))
    }

    @Test fun `append starts an array and keeps the newest rows within the cap`() {
        assertEquals(obj("""{ "m": [1] }"""), ViewData.append(obj("{}"), "m", JsonPrimitive(1)))
        assertEquals(obj("""{ "m": [1, 2] }"""), ViewData.append(obj("""{ "m": [1] }"""), "m", JsonPrimitive(2)))
        assertEquals(obj("""{ "m": [2] }"""), ViewData.append(obj("""{ "m": "scalar" }"""), "m", JsonPrimitive(2)).let { ViewData.clear(it, "x") })
        val full = ViewData.set(obj("{}"), "m", kotlinx.serialization.json.JsonArray((1..ViewLimits.MAX_ROWS).map(::JsonPrimitive)))
        val grown = ViewData.append(full, "m", JsonPrimitive(-1))
        val items = grown["m"] as kotlinx.serialization.json.JsonArray
        assertEquals(ViewLimits.MAX_ROWS, items.size)
        assertEquals(JsonPrimitive(-1), items.last())
        assertEquals(JsonPrimitive(2), items.first())
    }

    @Test fun `clear removes the key and ignores a missing path`() {
        assertEquals(obj("""{ "b": 1 }"""), ViewData.clear(obj("""{ "a": "x", "b": 1 }"""), "a"))
        assertEquals(obj("""{ "a": { } }"""), ViewData.clear(obj("""{ "a": { "b": 1 } }"""), "a.b"))
        assertEquals(obj("""{ "a": 1 }"""), ViewData.clear(obj("""{ "a": 1 }"""), "x.y"))
    }

    private val exec = { code: Int, out: String, err: String -> obj("""{ "exitCode": $code, "stdout": ${JsonPrimitive(out)}, "stderr": ${JsonPrimitive(err)} }""") }

    @Test fun `a json result from stdout is set at the path`() {
        val into = Into("containers", IntoMode.SET, ResultParse.JSON)
        val out = data(ViewData.write(obj("""{ "q": "x" }"""), into, exec(0, """[{"id":"a"}]""", "")))
        assertEquals(obj("""{ "q": "x", "containers": [{"id":"a"}] }"""), out)
    }

    @Test fun `text and lines results`() {
        val text = data(ViewData.write(obj("{}"), Into("t", IntoMode.SET, ResultParse.TEXT), exec(0, "hello\n", "")))
        assertEquals(obj("""{ "t": "hello" }"""), text)
        val lines = data(ViewData.write(obj("{}"), Into("l", IntoMode.SET, ResultParse.LINES), exec(0, "a\n\nb\n", "")))
        assertEquals(obj("""{ "l": ["a", "b"] }"""), lines)
    }

    @Test fun `append mode adds each element of a json array result`() {
        val into = Into("m", IntoMode.APPEND, ResultParse.JSON)
        val out = data(ViewData.write(obj("""{ "m": [0] }"""), into, exec(0, "[1, 2]", "")))
        assertEquals(obj("""{ "m": [0, 1, 2] }"""), out)
        val one = data(ViewData.write(obj("{}"), Into("m", IntoMode.APPEND, ResultParse.TEXT), exec(0, "reply\n", "")))
        assertEquals(obj("""{ "m": ["reply"] }"""), one)
    }

    @Test fun `the merge path merges a json object result by top-level key`() {
        val into = Into(ViewData.MERGE, IntoMode.SET, ResultParse.JSON)
        val out = data(ViewData.write(obj("""{ "query": "x", "name": "old" }"""), into, exec(0, """{ "name": "web", "state": "up" }""", "")))
        assertEquals(obj("""{ "query": "x", "name": "web", "state": "up" }"""), out)
        assertTrue(ViewData.write(obj("{}"), into, exec(0, "[1]", "")) is ViewData.Written.Rejected)
    }

    @Test fun `a failed exit is rejected with the last stderr line, unparseable json too`() {
        val failed = ViewData.write(obj("{}"), Into("x", IntoMode.SET, ResultParse.JSON), exec(1, "", "warn\nno such container\n")) as ViewData.Written.Rejected
        assertEquals("no such container", failed.reason)
        val silent = ViewData.write(obj("{}"), Into("x", IntoMode.SET, ResultParse.JSON), exec(3, "", "")) as ViewData.Written.Rejected
        assertEquals("exit code 3", silent.reason)
        val bad = ViewData.write(obj("{}"), Into("x", IntoMode.SET, ResultParse.JSON), exec(0, "not json", "")) as ViewData.Written.Rejected
        assertTrue(bad.reason.startsWith("the output is not JSON"))
    }

    @Test fun `a non-exec result is used as it is`() {
        val out = data(ViewData.write(obj("{}"), Into("n", IntoMode.SET, ResultParse.JSON), JsonPrimitive(5)))
        assertEquals(obj("""{ "n": 5 }"""), out)
    }

    @Test fun `effects apply in order`() {
        val start = obj("""{ "draft": "hi", "messages": [] }""")
        val done = listOf(
            ResolvedEffect(EffectOp.APPEND, "messages", obj("""{ "role": "user", "text": "hi" }""")),
            ResolvedEffect(EffectOp.CLEAR, "draft", null),
            ResolvedEffect(EffectOp.SET, "busy", JsonPrimitive(true)),
        ).fold(start) { d, e -> ViewData.apply(d, e) }
        assertEquals(obj("""{ "messages": [{ "role": "user", "text": "hi" }], "busy": true }"""), done)
    }
}
