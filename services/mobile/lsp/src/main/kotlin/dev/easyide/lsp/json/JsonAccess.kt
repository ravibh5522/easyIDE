package dev.easyide.lsp.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Total accessors over untrusted JSON. Server payloads vary (a number where a string was
 * expected, a missing optional, `null` for "absent"), so every read returns null on a shape
 * mismatch instead of throwing. Parsers built on these drop a malformed item rather than a
 * whole result, and need no try/catch.
 */

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val JsonElement?.arr: JsonArray? get() = this as? JsonArray

/** A JSON string's content; numbers and booleans are not coerced. */
val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An integral JSON number; `1.0` is accepted because some servers emit floats for ints. */
val JsonElement?.long: Long?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d % 1.0 == 0.0 && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) d.toLong() else null
    }

val JsonElement?.int: Int?
    get() = long?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** True for an absent value or JSON `null`. */
val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull

/** Maps every element with [f], dropping those it rejects. Non-arrays give an empty list. */
inline fun <T> JsonElement?.mapItems(f: (JsonElement) -> T?): List<T> =
    arr?.mapNotNull(f) ?: emptyList()

/** Strings of a string array; other elements dropped. */
val JsonElement?.strings: List<String> get() = mapItems { it.str }

/** Puts [value] only when it is non-null: optional LSP fields are absent, never `null`. */
fun JsonObjectBuilder.putOpt(key: String, value: JsonElement?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putOpt(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putOpt(key: String, value: Number?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putOpt(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun jsonStrings(values: Iterable<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

fun jsonInts(values: Iterable<Int>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
