package dev.easyide.extensions.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A JSON value whose string leaves are [Template]s (`setConfig.value`, `executeCommand.args`,
 * `lspRequest.params`). Parsed once at load; a leaf that is exactly one variable passes the
 * variable's JSON value through unconverted, so `"value": "${result:list}"` can set an array.
 */
sealed interface JsonTemplate {
    data class Leaf(val value: JsonElement) : JsonTemplate
    data class Text(val template: Template) : JsonTemplate
    data class Arr(val items: List<JsonTemplate>) : JsonTemplate
    data class Obj(val fields: Map<String, JsonTemplate>) : JsonTemplate

    /** Every template inside, for load-time checks. */
    fun templates(): List<Template> = when (this) {
        is Leaf -> emptyList()
        is Text -> listOf(template)
        is Arr -> items.flatMap { it.templates() }
        is Obj -> fields.values.flatMap { it.templates() }
    }

    companion object {
        /**
         * Builds the tree; [onError] receives the relative pointer of each malformed string
         * leaf, which is then kept as a literal (the manifest is refused anyway).
         */
        fun of(value: JsonElement, onError: (pointer: String, Template.Parse.Error) -> Unit, pointer: String = ""): JsonTemplate =
            when (value) {
                is JsonObject -> Obj(value.mapValues { (k, v) -> of(v, onError, "$pointer/" + k.replace("~", "~0").replace("/", "~1")) })
                is JsonArray -> Arr(value.mapIndexed { i, v -> of(v, onError, "$pointer/$i") })
                is JsonPrimitive -> if (!value.isString) Leaf(value) else when (val p = Template.parse(value.content)) {
                    is Template.Parse.Ok -> if (p.template.variables.isEmpty()) Leaf(value) else Text(p.template)
                    is Template.Parse.Error -> { onError(pointer, p); Leaf(value) }
                }
            }
    }
}
