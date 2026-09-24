package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The JSON Schema subset VS Code `configuration` properties use: `type` (one or
 * a list), `enum`, `const`, `minimum`/`maximum` (+ exclusive), `minLength`/
 * `maxLength`, `pattern`, `items`, `minItems`/`maxItems`, `uniqueItems`,
 * `properties`, `required`, `additionalProperties`, `anyOf`, `oneOf`.
 * Unknown keywords are ignored, which is JSON Schema's own rule: they never
 * make a value invalid.
 */
object SchemaValidator {

    /** Human-oriented violations with JSON-pointer-ish paths; empty means valid. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = ""): List<String> {
        val errors = ArrayList<String>()
        check(schema, value, path, errors)
        return errors
    }

    fun isValid(schema: JsonObject, value: JsonElement): Boolean = validate(schema, value).isEmpty()

    /** Schema `type` of [value]; `integer` for whole numbers so both `integer` and `number` accept them. */
    fun typeOf(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            isInteger(value) -> "integer"
            else -> "number"
        }
    }

    private fun check(schema: JsonObject, value: JsonElement, path: String, errors: MutableList<String>) {
        val types = types(schema)
        val actual = typeOf(value)
        if (types != null && types.none { it == actual || (it == "number" && actual == "integer") }) {
            errors += "$path: expected ${types.joinToString("|")}, got $actual"
            return
        }
        (schema["enum"] as? JsonArray)?.let { options ->
            if (options.none { same(it, value) }) errors += "$path: not one of the allowed values"
        }
        schema["const"]?.let { if (!same(it, value)) errors += "$path: must equal $it" }
        when (value) {
            is JsonPrimitive -> if (value.isString) checkString(schema, value.content, path, errors)
                else value.doubleOrNull?.let { checkNumber(schema, it, path, errors) }
            is JsonArray -> checkArray(schema, value, path, errors)
            is JsonObject -> checkObject(schema, value, path, errors)
            JsonNull -> Unit
        }
        (schema["anyOf"] as? JsonArray)?.let { branches ->
            if (branches.filterIsInstance<JsonObject>().none { validate(it, value, path).isEmpty() }) {
                errors += "$path: matches none of anyOf"
            }
        }
        (schema["oneOf"] as? JsonArray)?.let { branches ->
            val matching = branches.filterIsInstance<JsonObject>().count { validate(it, value, path).isEmpty() }
            if (matching != 1) errors += "$path: must match exactly one of oneOf"
        }
    }

    private fun checkString(schema: JsonObject, s: String, path: String, errors: MutableList<String>) {
        int(schema, "minLength")?.let { if (s.length < it) errors += "$path: shorter than $it" }
        int(schema, "maxLength")?.let { if (s.length > it) errors += "$path: longer than $it" }
        (schema["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.let { p ->
            // An uncompilable pattern constrains nothing; registration warns about it.
            val regex = runCatching { Regex(p.content) }.getOrNull()
            if (regex != null && !regex.containsMatchIn(s)) errors += "$path: does not match ${p.content}"
        }
    }

    private fun checkNumber(schema: JsonObject, d: Double, path: String, errors: MutableList<String>) {
        num(schema, "minimum")?.let { if (d < it) errors += "$path: below minimum $it" }
        num(schema, "maximum")?.let { if (d > it) errors += "$path: above maximum $it" }
        num(schema, "exclusiveMinimum")?.let { if (d <= it) errors += "$path: must be above $it" }
        num(schema, "exclusiveMaximum")?.let { if (d >= it) errors += "$path: must be below $it" }
    }

    private fun checkArray(schema: JsonObject, a: JsonArray, path: String, errors: MutableList<String>) {
        int(schema, "minItems")?.let { if (a.size < it) errors += "$path: fewer than $it items" }
        int(schema, "maxItems")?.let { if (a.size > it) errors += "$path: more than $it items" }
        if ((schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true && a.toSet().size != a.size) {
            errors += "$path: items must be unique"
        }
        (schema["items"] as? JsonObject)?.let { items ->
            a.forEachIndexed { i, item -> check(items, item, "$path/$i", errors) }
        }
    }

    private fun checkObject(schema: JsonObject, o: JsonObject, path: String, errors: MutableList<String>) {
        val props = schema["properties"] as? JsonObject
        (schema["required"] as? JsonArray)?.forEach { r ->
            val name = (r as? JsonPrimitive)?.content ?: return@forEach
            if (name !in o) errors += "$path: missing $name"
        }
        val additional = schema["additionalProperties"]
        for ((k, v) in o) {
            val propSchema = props?.get(k) as? JsonObject
            when {
                propSchema != null -> check(propSchema, v, "$path/$k", errors)
                additional is JsonObject -> check(additional, v, "$path/$k", errors)
                additional is JsonPrimitive && additional.booleanOrNull == false -> errors += "$path: unexpected $k"
            }
        }
    }

    private fun types(schema: JsonObject): List<String>? = when (val t = schema["type"]) {
        is JsonPrimitive -> listOf(t.content)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> null
    }

    /** Numbers compare by value so `1` and `1.0` match the same enum entry. */
    private fun same(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return x == y
        }
        return a == b
    }

    private fun isInteger(p: JsonPrimitive): Boolean = p.doubleOrNull?.let { it == Math.floor(it) && !it.isInfinite() } == true

    private fun num(schema: JsonObject, key: String): Double? =
        (schema[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun int(schema: JsonObject, key: String): Int? =
        (schema[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    /** Exposed for descriptor parsing, which reads the same numeric keywords. */
    internal fun number(schema: JsonObject, key: String): Double? = num(schema, key)

    internal fun stringOrNull(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
