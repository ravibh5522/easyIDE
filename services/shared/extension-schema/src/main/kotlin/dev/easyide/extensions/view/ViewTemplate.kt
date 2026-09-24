package dev.easyide.extensions.view

import dev.easyide.extensions.json.asText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A string prop split at load into literal text and `{field}` / `{field|format}` references
 * (extension-ui.md section 4.2). It is not the action language: `${...}` belongs to actions and
 * is plain text here. `{{` and `}}` write a literal brace. There are no expressions, so a view
 * can read its own data and nothing else.
 */
class ViewTemplate private constructor(val source: String, val parts: List<Part>) {

    sealed interface Part {
        data class Literal(val text: String) : Part
        data class Field(val path: String, val format: ViewFormat?) : Part
    }

    val fields: List<Part.Field> get() = parts.filterIsInstance<Part.Field>()

    /** The whole template is one field with no format: a JSON value passes through unconverted. */
    val single: Part.Field? get() = (parts.singleOrNull() as? Part.Field)?.takeIf { it.format == null }

    fun resolve(scope: ViewScope, now: Long = 0): String {
        val sb = StringBuilder()
        for (p in parts) when (p) {
            is Part.Literal -> sb.append(p.text)
            is Part.Field -> {
                val v = scope[p.path]?.takeUnless { it == JsonNull }
                if (v != null) sb.append(p.format?.apply(v, now) ?: v.asText())
            }
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is ViewTemplate && other.source == source
    override fun hashCode(): Int = source.hashCode()
    override fun toString(): String = source

    sealed interface Parse {
        data class Ok(val template: ViewTemplate) : Parse
        data class Error(val offset: Int, val message: String) : Parse
    }

    companion object {
        private val PATH = Regex("""\.|[A-Za-z_][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*""")

        fun literal(text: String) = ViewTemplate(text, if (text.isEmpty()) emptyList() else listOf(Part.Literal(text)))

        fun parse(text: String): Parse {
            val parts = ArrayList<Part>()
            val lit = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '{' && text.getOrNull(i + 1) == '{' -> { lit.append('{'); i += 2 }
                    c == '}' && text.getOrNull(i + 1) == '}' -> { lit.append('}'); i += 2 }
                    c == '}' -> return Parse.Error(i, "unmatched '}' (write '}}' for a literal brace)")
                    c == '{' -> {
                        val end = text.indexOf('}', i + 1)
                        if (end < 0) return Parse.Error(i, "unterminated '{'")
                        val field = field(text.substring(i + 1, end)) ?: return Parse.Error(i, "invalid field '{${text.substring(i + 1, end)}}'")
                        if (lit.isNotEmpty()) { parts += Part.Literal(lit.toString()); lit.clear() }
                        parts += field
                        i = end + 1
                    }
                    else -> { lit.append(c); i++ }
                }
            }
            if (lit.isNotEmpty()) parts += Part.Literal(lit.toString())
            return Parse.Ok(ViewTemplate(text, parts))
        }

        private fun field(body: String): Part.Field? {
            val path = body.substringBefore('|').trim()
            if (!PATH.matches(path)) return null
            if ('|' !in body) return Part.Field(path, null)
            val format = ViewFormat.parse(body.substringAfter('|').trim()) ?: return null
            return Part.Field(path, format)
        }
    }
}

/**
 * A JSON value whose string leaves are [ViewTemplate]s (`args`, effect values). A leaf that is
 * exactly one unformatted field keeps the field's JSON type, so `"count": "{n}"` sends a number.
 */
sealed interface ViewValue {
    data class Leaf(val value: JsonElement) : ViewValue
    data class Text(val template: ViewTemplate) : ViewValue
    data class Arr(val items: List<ViewValue>) : ViewValue
    data class Obj(val fields: Map<String, ViewValue>) : ViewValue

    fun templates(): List<ViewTemplate> = when (this) {
        is Leaf -> emptyList()
        is Text -> listOf(template)
        is Arr -> items.flatMap { it.templates() }
        is Obj -> fields.values.flatMap { it.templates() }
    }

    fun resolve(scope: ViewScope, now: Long = 0): JsonElement = when (this) {
        is Leaf -> value
        is Text -> template.single?.let { scope[it.path] } ?: JsonPrimitive(template.resolve(scope, now))
        is Arr -> JsonArray(items.map { it.resolve(scope, now) })
        is Obj -> JsonObject(fields.mapValues { (_, v) -> v.resolve(scope, now) })
    }

    companion object {
        /** [onError] receives the pointer (relative to [pointer]) of each malformed string leaf; it is kept literal. */
        fun of(value: JsonElement, onError: (String, ViewTemplate.Parse.Error) -> Unit, pointer: String = ""): ViewValue = when (value) {
            is JsonObject -> Obj(value.mapValues { (k, v) -> of(v, onError, "$pointer/" + k.replace("~", "~0").replace("/", "~1")) })
            is JsonArray -> Arr(value.mapIndexed { i, v -> of(v, onError, "$pointer/$i") })
            is JsonPrimitive -> if (!value.isString) Leaf(value) else when (val p = ViewTemplate.parse(value.content)) {
                is ViewTemplate.Parse.Ok -> if (p.template.fields.isEmpty() && '{' !in value.content && '}' !in value.content) Leaf(value) else Text(p.template)
                is ViewTemplate.Parse.Error -> { onError(pointer, p); Leaf(value) }
            }
        }
    }
}
