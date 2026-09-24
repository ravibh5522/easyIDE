package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoncTest {

    private fun failure(text: String) = Jsonc.parse(text) as JsoncResult.Failure

    @Test
    fun acceptsCommentsAndTrailingCommas() {
        val v = json(
            """
            // line comment
            {
              /* block */ "a": 1,
              "b": [true, false, null,],
              "c": {"d": "x\n\u0041",},
            }
            """.trimIndent(),
        ) as JsonObject
        assertEquals("1", (v["a"] as JsonPrimitive).content)
        assertEquals(3, (v["b"] as JsonArray).size)
        assertEquals("x\nA", ((v["c"] as JsonObject)["d"] as JsonPrimitive).content)
    }

    @Test
    fun keepsNumbersAsWritten() {
        assertEquals("1.0", (json("[1.0]") as JsonArray)[0].toString())
        assertEquals("-2e10", (json("[-2e10]") as JsonArray)[0].toString())
    }

    @Test
    fun reportsErrorsWithOffsets() {
        assertEquals(JsoncError.EXPECTED_COLON, failure("{\"a\" 1}").error)
        assertEquals(5, failure("{\"a\" 1}").offset)
        assertEquals(JsoncError.UNTERMINATED_STRING, failure("{\"a").error)
        assertEquals(JsoncError.UNTERMINATED_COMMENT, failure("{} /*").error)
        assertEquals(JsoncError.TRAILING_CONTENT, failure("{} x").error)
        assertEquals(JsoncError.INVALID_NUMBER, failure("[-x]").error)
        assertEquals(JsoncError.EXPECTED_KEY, failure("{a: 1}").error)
        assertEquals(JsoncError.UNEXPECTED_END, failure("[1,").error)
        assertEquals(JsoncError.INVALID_ESCAPE, failure("\"\\q\"").error)
    }

    @Test
    fun enforcesDepthBeforeRecursing() {
        val deep = "[".repeat(SettingsPolicy.MAX_JSON_DEPTH + 2) + "]".repeat(SettingsPolicy.MAX_JSON_DEPTH + 2)
        assertEquals(JsoncError.TOO_DEEP, failure(deep).error)
        val ok = "[".repeat(SettingsPolicy.MAX_JSON_DEPTH) + "]".repeat(SettingsPolicy.MAX_JSON_DEPTH)
        assertTrue(Jsonc.parse(ok) is JsoncResult.Ok)
        // Far past any stack: must still be a clean error, not a StackOverflowError.
        val hostile = "[".repeat(200_000)
        assertEquals(JsoncError.TOO_DEEP, failure(hostile).error)
    }

    @Test
    fun rejectsOversizedText() {
        assertEquals(JsoncError.TOO_LARGE, failure(" ".repeat(SettingsPolicy.MAX_FILE_BYTES.toInt() + 1)).error)
    }

    @Test
    fun linesAreOneBased() {
        assertEquals(1, Jsonc.lineOf("abc", 1))
        assertEquals(3, Jsonc.lineOf("a\nb\nc", 4))
    }
}
