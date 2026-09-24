package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why one `keyRows.layouts` entry (or key) was skipped; [index] is the row's position. */
data class KeyRowProblem(val index: Int, val rowId: String?, val message: String)

data class UserKeyRowsResult(val rows: List<KeyRowContribution>, val problems: List<KeyRowProblem>)

/**
 * The user's `keyRows.layouts` (sdk-reference: `[{id, title, keys[...]}]`, G scope, part of
 * the active profile): the same shape as a contributed `easyide.keyRows` entry, `when`
 * optional. A row whose id equals an existing row replaces it ([KeyRows]). The user typed
 * it, so a bad key drops that key and a bad row drops that row, each reported; the rest
 * still applies.
 */
object UserKeyRows {

    private val ACTIONS = listOf("insert", "snippet", "key", "command")

    fun decode(value: JsonElement?): UserKeyRowsResult {
        val array = value as? JsonArray ?: return UserKeyRowsResult(emptyList(), emptyList())
        val rows = ArrayList<KeyRowContribution>()
        val problems = ArrayList<KeyRowProblem>()
        val seen = HashSet<String>()
        array.forEachIndexed { i, element ->
            val o = element as? JsonObject
            val id = o?.str("id")?.trim()?.takeIf { it.isNotEmpty() }
            if (o == null || id == null) { problems += KeyRowProblem(i, null, "a row needs an object with a non-empty id"); return@forEachIndexed }
            if (!seen.add(id)) { problems += KeyRowProblem(i, id, "duplicate id; the first row with it wins"); return@forEachIndexed }
            val whenText = o.str("when")
            val whenExpr = whenText?.let { text ->
                when (val r = WhenParser.parse(text)) {
                    is WhenParseResult.Ok -> r.expr
                    is WhenParseResult.Error -> { problems += KeyRowProblem(i, id, "when: column ${r.offset + 1}: ${r.message}"); return@forEachIndexed }
                }
            }
            val keys = (o["keys"] as? JsonArray).orEmpty().mapIndexedNotNull { j, k ->
                rowKey(k as? JsonObject) ?: run { problems += KeyRowProblem(i, id, "key ${j + 1}: needs a label and at most one of insert, snippet, key or command"); null }
            }
            if (keys.isEmpty()) { problems += KeyRowProblem(i, id, "a row needs at least one key"); return@forEachIndexed }
            rows += KeyRowContribution(id, o.str("title")?.takeIf { it.isNotBlank() } ?: id, whenExpr, keys)
        }
        return UserKeyRowsResult(rows, problems)
    }

    /** A key with no action inserts its label, like a contributed row's `{ "label": ":" }`. */
    private fun rowKey(o: JsonObject?): RowKey? {
        val label = o?.str("label")?.takeIf { it.isNotEmpty() } ?: return null
        val action = if (ACTIONS.none { it in o }) KeyAction.Insert(label) else action(o) ?: return null
        val longPress = when (val lp = o["longPress"]) {
            null -> null
            is JsonObject -> action(lp) ?: return null
            else -> return null
        }
        return RowKey(label, action, longPress)
    }

    private fun action(o: JsonObject): KeyAction? {
        val present = ACTIONS.filter { it in o }
        val field = present.singleOrNull() ?: return null
        val text = o.str(field)?.takeIf { it.isNotEmpty() } ?: return null
        return when (field) {
            "insert" -> KeyAction.Insert(text)
            "snippet" -> KeyAction.Snippet(text)
            "key" -> KeyAction.Key(text)
            else -> KeyAction.Command(text)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
