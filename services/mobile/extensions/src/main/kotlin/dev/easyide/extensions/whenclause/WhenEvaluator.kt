package dev.easyide.extensions.whenclause

import dev.easyide.extensions.json.booleanOrNull
import dev.easyide.extensions.json.numberOrNull
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Read access to context values; null means undefined (sdk-reference: unknown keys are undefined). */
fun interface ContextLookup {
    operator fun get(key: String): JsonElement?
}

/**
 * Pure evaluation over a snapshot (extension-runtime.md sec 6.2). Never throws: an unknown
 * key or a type mismatch is simply false, so a bad clause hides a button instead of
 * breaking the menu it is in.
 */
object WhenEvaluator {

    fun evaluate(e: WhenExpr, ctx: ContextLookup): Boolean = when (e) {
        is WhenExpr.Const -> e.value
        is WhenExpr.Key -> truthy(ctx[e.name])
        is WhenExpr.Not -> !evaluate(e.expr, ctx)
        is WhenExpr.And -> e.parts.all { evaluate(it, ctx) }
        is WhenExpr.Or -> e.parts.any { evaluate(it, ctx) }
        is WhenExpr.Compare -> compare(ctx[e.key], e.op, e.literal)
        is WhenExpr.Matches -> ctx[e.key]?.stringOrNull?.let { e.regex.containsMatchIn(it) } == true
        is WhenExpr.In -> contains(ctx[e.key], e.container, e.negated, ctx)
    }

    /** Falsy: undefined, null, false, "", 0. Arrays and objects are truthy even when empty (JS). */
    fun truthy(v: JsonElement?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            v.isString -> v.content.isNotEmpty()
            v.booleanOrNull != null -> v.booleanOrNull == true
            else -> v.numberOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
        }
        else -> true
    }

    private fun compare(v: JsonElement?, op: CompareOp, literal: String): Boolean {
        if (v == null) return op == CompareOp.NE
        return when (op) {
            CompareOp.EQ -> equalsLiteral(v, literal)
            CompareOp.NE -> !equalsLiteral(v, literal)
            else -> {
                val a = asNumber(v) ?: return false
                val b = literal.toDoubleOrNull() ?: return false
                when (op) {
                    CompareOp.LT -> a < b
                    CompareOp.LE -> a <= b
                    CompareOp.GT -> a > b
                    else -> a >= b
                }
            }
        }
    }

    /**
     * String comparison of the stringified value (sdk-reference), except that a numeric value
     * against a numeric literal compares as numbers, so `tabSize == 4` holds for a stored 4.0.
     */
    private fun equalsLiteral(v: JsonElement, literal: String): Boolean {
        if (v is JsonPrimitive && !v.isString && v != JsonNull) {
            val n = v.numberOrNull
            val l = literal.toDoubleOrNull()
            if (n != null && l != null) return n == l
        }
        return stringify(v) == literal
    }

    private fun stringify(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    private fun asNumber(v: JsonElement): Double? = when {
        v is JsonPrimitive && v.isString -> v.content.toDoubleOrNull()
        else -> v.numberOrNull
    }

    private fun contains(v: JsonElement?, c: WhenExpr.Container, negated: Boolean, ctx: ContextLookup): Boolean {
        val needle = v?.let { if (it is JsonPrimitive && it != JsonNull) it.content else null } ?: return false
        val found = when (c) {
            is WhenExpr.Container.Literal -> needle in c.items
            is WhenExpr.Container.KeyRef -> when (val target = ctx[c.name]) {
                is JsonArray -> target.any { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content == needle }
                is JsonObject -> target.containsKey(needle)
                else -> false
            }
        }
        return if (negated) !found else found
    }
}
