package dev.easyide.extensions.view

import dev.easyide.extensions.action.ExecResultJson
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.asText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The view's data object and the only ways it changes (extension-ui.md section 4.4): a provider's update merges
 * top-level keys (so a field's typed text survives a refresh), an effect edits one path, and an action's result
 * is written back at its `as` path. Pure and total: a bad path or a malformed result changes nothing.
 */
object ViewData {

    /** Outcome of writing an action result into the data. */
    sealed interface Written {
        data class Data(val data: JsonObject) : Written
        data class Rejected(val reason: String) : Written
    }

    /**
     * RFC 7386-style merge at the top level only: keys in [update] replace, `null` removes. A provider that sends
     * [ViewLimits.MAX_UPDATE_BYTES] or more is refused whole, so one oversized update cannot stall the UI.
     */
    fun merge(data: JsonObject, update: JsonElement): Written {
        val patch = update as? JsonObject ?: return Written.Rejected("view data must be a JSON object")
        if (patch.toString().length > ViewLimits.MAX_UPDATE_BYTES) return Written.Rejected("update larger than ${ViewLimits.MAX_UPDATE_BYTES / 1024} KB")
        val out = LinkedHashMap(data)
        for ((k, v) in patch) if (v == JsonNull) out.remove(k) else out[k] = v
        return Written.Data(JsonObject(out))
    }

    /**
     * Why a `sandboxExec` result failed: its last stderr line, or the exit code when it said nothing; null for a result that
     * succeeded or is not an exec result. A command that exits non-zero is a completed step, so this is how a view learns it failed.
     */
    fun execFailure(result: JsonElement): String? {
        val exec = result as? JsonObject ?: return null
        if (ExecResultJson.EXIT_CODE !in exec || ExecResultJson.STDOUT !in exec) return null
        val code = (exec[ExecResultJson.EXIT_CODE] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (code == 0) return null
        val stderr = (exec[ExecResultJson.STDERR] as? JsonPrimitive)?.content.orEmpty()
        return stderr.trim().lineSequence().lastOrNull().orEmpty().ifEmpty { "exit code $code" }
    }

    fun get(data: JsonObject, path: String): JsonElement? = ViewScope.of(data)[path]

    /** The [Into] path that merges a JSON object result into the data by top-level key, as a provider's update does. */
    const val MERGE = "."

    /** [path] set to [value], creating objects on the way; an existing non-object on the way is replaced. */
    fun set(data: JsonObject, path: String, value: JsonElement): JsonObject = update(data, path.split('.')) { value }

    /**
     * [value] added at the end of the array at [path] (a missing or non-array value becomes a one-item array). Arrays
     * keep their newest [ViewLimits.MAX_ROWS] items, so an append loop cannot grow without bound.
     */
    fun append(data: JsonObject, path: String, value: JsonElement): JsonObject = update(data, path.split('.')) { old ->
        val items = (old as? JsonArray)?.toList().orEmpty() + value
        JsonArray(items.takeLast(ViewLimits.MAX_ROWS))
    }

    /** The key at [path] removed, which a bound field shows as empty; a path that is not there changes nothing. */
    fun clear(data: JsonObject, path: String): JsonObject = remove(data, path.split('.'))

    private fun remove(data: JsonObject, keys: List<String>): JsonObject {
        val head = keys.first()
        if (keys.size == 1) return JsonObject(data - head)
        val child = data[head] as? JsonObject ?: return data
        return JsonObject(data + (head to remove(child, keys.drop(1))))
    }

    private fun update(data: JsonObject, keys: List<String>, change: (JsonElement?) -> JsonElement): JsonObject {
        val head = keys.first()
        val next = if (keys.size == 1) change(data[head]) else update(data[head] as? JsonObject ?: JsonObject(emptyMap()), keys.drop(1), change)
        return JsonObject(data + (head to next))
    }

    fun apply(data: JsonObject, effect: ResolvedEffect): JsonObject = when (effect.op) {
        EffectOp.SET -> set(data, effect.path, effect.value ?: JsonNull)
        EffectOp.APPEND -> append(data, effect.path, effect.value ?: JsonNull)
        EffectOp.CLEAR -> clear(data, effect.path)
    }

    /**
     * An action's [result] written where [into] says. A `sandboxExec` result is `{exitCode, stdout, stderr}`: a
     * non-zero exit is [Written.Rejected] with the stderr tail, and [ResultParse] reads stdout; any other result is
     * used as it is.
     */
    fun write(data: JsonObject, into: Into, result: JsonElement): Written {
        execFailure(result)?.let { return Written.Rejected(it) }
        val exec = result as? JsonObject
        val isExec = exec != null && ExecResultJson.EXIT_CODE in exec && ExecResultJson.STDOUT in exec
        val text = if (isExec) (exec!![ExecResultJson.STDOUT] as? JsonPrimitive)?.content.orEmpty() else result.asText()
        val value: JsonElement = when (into.parse) {
            ResultParse.TEXT -> JsonPrimitive(text.removeSuffix("\n"))
            ResultParse.LINES -> JsonArray(text.lines().filter { it.isNotEmpty() }.map(::JsonPrimitive))
            ResultParse.JSON -> if (!isExec) result else when (val p = JsonText.parseStrict(text)) {
                is JsonParse.Ok -> p.value
                is JsonParse.Error -> return Written.Rejected("the output is not JSON (${p.message})")
            }
        }
        if (into.path == MERGE) return merge(data, value)
        return Written.Data(when (into.mode) {
            IntoMode.SET -> set(data, into.path, value)
            IntoMode.APPEND -> (value as? JsonArray)?.fold(data) { d, item -> append(d, into.path, item) } ?: append(data, into.path, value)
        })
    }
}

/** An [Effect] with its value resolved against the scope it fires in. */
data class ResolvedEffect(val op: EffectOp, val path: String, val value: JsonElement?)
