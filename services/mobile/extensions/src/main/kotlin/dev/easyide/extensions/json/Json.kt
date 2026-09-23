package dev.easyide.extensions.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON handling for the runtime. The tree type is kotlinx [JsonElement] (immutable), used
 * for manifests, action arguments, results and setting values alike, so nothing converts
 * between two JSON models.
 */
sealed interface JsonParse {
    data class Ok(val value: JsonElement) : JsonParse

    /** [line] and [column] are 1-based; 0 when the parser did not report a position. */
    data class Error(val line: Int, val column: Int, val message: String) : JsonParse
}

object JsonText {
    // Manifests must stay readable by VS Code tooling, so no comments or trailing commas.
    private val strict = Json

    // VS Code content files (themes, language-configuration, snippets) are JSONC in practice.
    @OptIn(ExperimentalSerializationApi::class)
    private val lenient = Json { allowComments = true; allowTrailingComma = true }

    private val offsetPattern = Regex("""offset (\d+)""")

    fun parseStrict(text: String): JsonParse = parse(strict, text)

    fun parseLenient(text: String): JsonParse = parse(lenient, text)

    // Untrusted input boundary: the parser reports syntax errors by exception only.
    private fun parse(json: Json, text: String): JsonParse = try {
        JsonParse.Ok(json.parseToJsonElement(text))
    } catch (e: SerializationException) {
        val raw = e.message.orEmpty()
        val offset = offsetPattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val summary = raw.lineSequence().first()
        if (offset == null) JsonParse.Error(0, 0, summary) else {
            val (line, col) = lineColumn(text, offset)
            JsonParse.Error(line, col, summary)
        }
    }

    private fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') { line++; col = 1 } else col++
        }
        return line to col
    }
}

/** RFC 6901 pointers; the empty string is the document root. */
object JsonPointer {
    fun child(parent: String, key: String): String =
        parent + "/" + key.replace("~", "~0").replace("/", "~1")

    fun index(parent: String, index: Int): String = "$parent/$index"
}

/** The string content of a JSON string primitive, else null (numbers are not strings). */
val JsonElement.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement.booleanOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

val JsonElement.numberOrNull: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.content?.toDoubleOrNull()

/** An integral number that fits an Int, else null (1.0 counts, 1.5 does not). */
val JsonElement.intOrNull: Int?
    get() {
        val d = numberOrNull ?: return null
        if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return null
        return d.toInt()
    }

/** JSON Schema type name of a value, for messages and the `type` keyword. */
val JsonElement.schemaType: String
    get() = when (this) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        JsonNull -> "null"
        is JsonPrimitive -> when {
            isString -> "string"
            booleanOrNull != null -> "boolean"
            intOrNull != null || isIntegralLiteral(content) -> "integer"
            else -> "number"
        }
    }

private fun isIntegralLiteral(s: String): Boolean = s.toDoubleOrNull()?.let { it == Math.floor(it) && !it.isInfinite() } == true

/** Strings as their content, everything else as compact JSON: how values enter templates. */
fun JsonElement.asText(): String = stringOrNull ?: toString()

/** Structural equality where 1 and 1.0 are the same number (JSON Schema `enum`/`const`). */
fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonObject && b is JsonObject ->
        a.size == b.size && a.all { (k, v) -> b[k]?.let { jsonEquals(v, it) } == true }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
    a is JsonPrimitive && b is JsonPrimitive -> {
        val an = a.numberOrNull
        val bn = b.numberOrNull
        if (an != null && bn != null && !a.isString && !b.isString) an == bn
        else a.isString == b.isString && a.content == b.content && (a is JsonNull) == (b is JsonNull)
    }
    else -> false
}
