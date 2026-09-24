package dev.easyide.extensions.view

import dev.easyide.extensions.json.asText
import dev.easyide.extensions.json.numberOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What `{field}` reads: the current item (a list row, a tree node) first, then the enclosing
 * scopes up to the view's data object. A path is `a.b.0.c`: object keys and array indexes only,
 * so a view can read its data and nothing else.
 */
class ViewScope(val data: JsonElement, private val parent: ViewScope? = null) {

    fun child(item: JsonElement) = ViewScope(item, this)

    operator fun get(path: String): JsonElement? {
        if (path == ITEM) return data
        return walk(data, path) ?: parent?.get(path)
    }

    /** The text of [path], empty when missing: a missing field renders as nothing, never as an error. */
    fun text(path: String): String = get(path)?.takeUnless { it == JsonNull }?.asText().orEmpty()

    private fun walk(from: JsonElement, path: String): JsonElement? {
        var at: JsonElement = from
        for (segment in path.split('.')) {
            at = when (at) {
                is JsonObject -> at[segment]
                is JsonArray -> segment.toIntOrNull()?.let { at.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return at
    }

    companion object {
        /** The current item itself: `{.}`. */
        const val ITEM = "."

        fun of(data: JsonElement) = ViewScope(data)
    }
}

/** `{field|format}` formats (extension-ui.md section 4.2). [now] is epoch milliseconds, for `relative`. */
enum class ViewFormat(val wire: String) {
    BYTES("bytes"), DURATION("duration"), RELATIVE("relative"), COUNT("count");

    fun apply(value: JsonElement, now: Long): String {
        val n = (value as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.numberOrNull }
            ?: return value.asText()
        return when (this) {
            BYTES -> bytes(n)
            DURATION -> duration(n.toLong())
            RELATIVE -> relative(n.toLong(), now)
            COUNT -> count(n)
        }
    }

    companion object {
        fun parse(wire: String): ViewFormat? = entries.firstOrNull { it.wire == wire }

        private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

        fun bytes(n: Double): String {
            var v = n
            var unit = 0
            while (v >= 1024 && unit < UNITS.lastIndex) { v /= 1024; unit++ }
            return if (unit == 0) "${v.toLong()} B" else "%.1f %s".format(java.util.Locale.ROOT, v, UNITS[unit])
        }

        /** Seconds as `45s`, `3m 05s`, `2h 10m`, `1d 4h`. */
        fun duration(seconds: Long): String {
            val s = maxOf(seconds, 0)
            return when {
                s < 60 -> "${s}s"
                s < 3_600 -> "${s / 60}m %02ds".format(java.util.Locale.ROOT, s % 60)
                s < 86_400 -> "${s / 3_600}h %02dm".format(java.util.Locale.ROOT, s % 3_600 / 60)
                else -> "${s / 86_400}d ${s % 86_400 / 3_600}h"
            }
        }

        /** [epochMillis] against [now]: `just now`, `5m ago`, `3h ago`, `2d ago`; a future time reads `in 5m`. */
        fun relative(epochMillis: Long, now: Long): String {
            val delta = (now - epochMillis) / 1_000
            val span = duration(kotlin.math.abs(delta)).substringBefore(' ')
            return when {
                kotlin.math.abs(delta) < 5 -> "just now"
                delta > 0 -> "$span ago"
                else -> "in $span"
            }
        }

        /** `950`, `1.2k`, `3.4M`. */
        fun count(n: Double): String = when {
            kotlin.math.abs(n) < 1_000 -> n.toLong().toString()
            kotlin.math.abs(n) < 1_000_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1_000)
            else -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000)
        }
    }
}
