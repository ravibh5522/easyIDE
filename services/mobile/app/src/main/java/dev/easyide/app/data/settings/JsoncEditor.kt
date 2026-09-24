package dev.easyide.app.data.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One property change in a settings layer; a null [value] removes the key. */
data class SettingEdit(val key: String, val language: String? = null, val value: JsonElement?)

/**
 * Minimal edits to a JSONC settings text: replaces, inserts or removes one
 * property (top level, or inside a `"[lang]"` block) and leaves every other
 * byte - comments, ordering, formatting - as it was. Project files live in git,
 * so a rewrite that dropped comments would be a diff the user never asked for.
 */
object JsoncEditor {

    /**
     * Applies [edits] in order. Returns null when [text] is not a JSONC object:
     * editing a broken file could only make it worse, so the caller refuses.
     */
    fun apply(text: String, edits: List<SettingEdit>): String? =
        edits.fold(text.ifBlank { EMPTY } as String?) { acc, edit -> acc?.let { set(it, edit) } }

    fun set(text: String, edit: SettingEdit): String? {
        val root = (Jsonc.parse(text) as? JsoncResult.Ok)?.root ?: return null
        if (root.value !is JsonObject) return null
        val lang = edit.language ?: return setIn(text, root, edit.key, edit.value)
        val blockKey = "[$lang]"
        val block = root.members.lastOrNull { it.key == blockKey && it.node.value is JsonObject }
        if (block != null) return setIn(text, block.node, edit.key, edit.value)
        val value = edit.value ?: return text
        return setIn(text, root, blockKey, JsonObject(mapOf(edit.key to value)))
    }

    /** Pretty JSON with two-space indent, the format files are created in. */
    @OptIn(ExperimentalSerializationApi::class)
    fun render(value: JsonElement): String = PRETTY.encodeToString(JsonElement.serializer(), value)

    private fun setIn(text: String, obj: JsoncNode, key: String, value: JsonElement?): String {
        val existing = obj.members.lastOrNull { it.key == key }
        return when {
            existing != null && value != null -> {
                val indent = indentOf(text, existing.keyStart)
                text.replaceRange(existing.node.start, existing.node.end, reindent(render(value), indent))
            }
            existing != null -> remove(text, obj, existing)
            value != null -> insert(text, obj, key, value)
            else -> text
        }
    }

    private fun remove(text: String, obj: JsoncNode, member: JsoncMember): String {
        val index = obj.members.indexOf(member)
        var start = member.keyStart
        var end = if (member.commaAt >= 0) member.commaAt + 1 else member.node.end
        // The last member has no comma of its own; drop the one before it so the
        // object does not end in a dangling (if legal) trailing comma.
        if (member.commaAt < 0 && index > 0) {
            val prev = obj.members[index - 1]
            if (prev.commaAt >= 0) start = prev.commaAt
        }
        // Take the whole line when the member was alone on it.
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        if (text.substring(lineStart, start).isBlank() && start == member.keyStart) start = lineStart
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (text.substring(end, lineEnd).isBlank() && start == lineStart) end = (lineEnd + 1).coerceAtMost(text.length)
        return text.removeRange(start, end)
    }

    private fun insert(text: String, obj: JsoncNode, key: String, value: JsonElement): String {
        val closeAt = obj.end - 1
        val baseIndent = indentOf(text, obj.start)
        val last = obj.members.lastOrNull()
        val indent = last?.let { indentOf(text, it.keyStart) } ?: (baseIndent + INDENT)
        val member = "\"${escape(key)}\": ${reindent(render(value), indent)}"
        if (last == null) {
            val inner = text.substring(obj.start + 1, closeAt)
            return if (inner.isBlank()) {
                text.replaceRange(obj.start + 1, closeAt, "\n$indent$member\n$baseIndent")
            } else {
                text.replaceRange(obj.start + 1, obj.start + 1, "\n$indent$member")
            }
        }
        return if (last.commaAt >= 0) {
            text.replaceRange(last.commaAt + 1, last.commaAt + 1, "\n$indent$member,")
        } else {
            text.replaceRange(last.node.end, last.node.end, ",\n$indent$member")
        }
    }

    /** Whitespace between the start of [offset]'s line and the first non-blank character. */
    private fun indentOf(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }

    private fun reindent(rendered: String, indent: String): String = rendered.replace("\n", "\n$indent")

    private fun escape(key: String): String = render(kotlinx.serialization.json.JsonPrimitive(key)).drop(1).dropLast(1)

    private const val EMPTY = "{}"
    private const val INDENT = "  "

    @OptIn(ExperimentalSerializationApi::class)
    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = INDENT }
}
