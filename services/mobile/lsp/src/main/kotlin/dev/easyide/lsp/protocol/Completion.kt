package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.bool
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import dev.easyide.lsp.json.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** LSP `CompletionItemKind` 1..25; unknown values map to null (the UI shows a neutral icon). */
enum class CompletionItemKind(val wire: Int) {
    TEXT(1), METHOD(2), FUNCTION(3), CONSTRUCTOR(4), FIELD(5), VARIABLE(6), CLASS(7), INTERFACE(8),
    MODULE(9), PROPERTY(10), UNIT(11), VALUE(12), ENUM(13), KEYWORD(14), SNIPPET(15), COLOR(16),
    FILE(17), REFERENCE(18), FOLDER(19), ENUM_MEMBER(20), CONSTANT(21), STRUCT(22), EVENT(23),
    OPERATOR(24), TYPE_PARAMETER(25);

    companion object {
        fun fromWire(v: Int?): CompletionItemKind? = entries.firstOrNull { it.wire == v }
    }
}

enum class InsertTextFormat(val wire: Int) {
    PLAIN_TEXT(1), SNIPPET(2);

    companion object {
        fun fromWire(v: Int?): InsertTextFormat = if (v == SNIPPET.wire) SNIPPET else PLAIN_TEXT
    }
}

/** The main edit of a completion: a plain replace, or insert-vs-replace ranges. */
sealed interface CompletionEdit {
    val newText: String

    data class Replace(val edit: TextEdit) : CompletionEdit {
        override val newText: String get() = edit.newText
    }

    data class InsertReplace(override val newText: String, val insert: Range, val replace: Range) : CompletionEdit

    companion object {
        fun fromJson(e: JsonElement?): CompletionEdit? {
            val o = e.obj ?: return null
            val text = o["newText"].str ?: return null
            Range.fromJson(o["range"])?.let { return Replace(TextEdit(it, text)) }
            val insert = Range.fromJson(o["insert"]) ?: return null
            val replace = Range.fromJson(o["replace"]) ?: return null
            return InsertReplace(text, insert, replace)
        }
    }
}

/**
 * A completion item with the list's `itemDefaults` already applied. [raw] (defaults merged)
 * is what `completionItem/resolve` sends back, so server-private `data` survives.
 */
data class CompletionItem(
    val label: String,
    val labelDetail: String?,
    val labelDescription: String?,
    val kind: CompletionItemKind?,
    val deprecated: Boolean,
    val detail: String?,
    val documentation: Markup?,
    val preselect: Boolean,
    val sortText: String,
    val filterText: String,
    val insertText: String,
    val insertTextFormat: InsertTextFormat,
    val edit: CompletionEdit?,
    val additionalTextEdits: List<TextEdit>,
    val commitCharacters: List<String>,
    val command: Command?,
    val raw: JsonObject,
) {
    companion object {
        private const val TAG_DEPRECATED = 1

        fun fromJson(e: JsonElement?): CompletionItem? {
            val o = e.obj ?: return null
            val label = o["label"].str ?: return null
            val details = o["labelDetails"].obj
            val tagDeprecated = o["tags"].mapItems { it.int }.contains(TAG_DEPRECATED)
            return CompletionItem(
                label = label,
                labelDetail = details?.get("detail").str,
                labelDescription = details?.get("description").str,
                kind = CompletionItemKind.fromWire(o["kind"].int),
                deprecated = tagDeprecated || o["deprecated"].bool == true,
                detail = o["detail"].str,
                documentation = Markup.fromJson(o["documentation"]),
                preselect = o["preselect"].bool == true,
                sortText = o["sortText"].str ?: label,
                filterText = o["filterText"].str ?: label,
                insertText = o["insertText"].str ?: label,
                insertTextFormat = InsertTextFormat.fromWire(o["insertTextFormat"].int),
                edit = CompletionEdit.fromJson(o["textEdit"]),
                additionalTextEdits = TextEdit.listFromJson(o["additionalTextEdits"]),
                commitCharacters = o["commitCharacters"].strings,
                command = Command.fromJson(o["command"]),
                raw = o,
            )
        }
    }
}

data class CompletionList(val isIncomplete: Boolean, val items: List<CompletionItem>) {
    companion object {
        val EMPTY = CompletionList(false, emptyList())

        /** Parses `CompletionItem[] | CompletionList | null`, applying `itemDefaults`. */
        fun fromJson(e: JsonElement?): CompletionList {
            e.arr?.let { items -> return CompletionList(false, items.mapNotNull(CompletionItem::fromJson)) }
            val o = e.obj ?: return EMPTY
            val defaults = o["itemDefaults"].obj
            val items = o["items"].mapItems { item ->
                val io = item.obj ?: return@mapItems null
                CompletionItem.fromJson(if (defaults == null) io else applyDefaults(io, defaults))
            }
            return CompletionList(o["isIncomplete"].bool == true, items)
        }

        /** LSP 3.17 `itemDefaults`: each default fills the field only where the item lacks it. */
        private fun applyDefaults(item: JsonObject, defaults: JsonObject): JsonObject = buildJsonObject {
            item.forEach { (k, v) -> put(k, v) }
            for (key in listOf("commitCharacters", "insertTextFormat", "insertTextMode", "data")) {
                val value = defaults[key]
                if (value != null && !item.containsKey(key)) put(key, value)
            }
            val editRange = defaults["editRange"].obj
            if (editRange != null && !item.containsKey("textEdit")) {
                val text = item["textEditText"].str ?: item["insertText"].str ?: item["label"].str.orEmpty()
                // `editRange` is either a Range or `{insert, replace}`.
                put("textEdit", buildJsonObject {
                    if (editRange.containsKey("start")) put("range", editRange) else editRange.forEach { (k, v) -> put(k, v) }
                    put("newText", JsonPrimitive(text))
                })
            }
        }
    }
}

/** `CompletionContext.triggerKind` (lsp-features.md 4.2). */
enum class CompletionTriggerKind(val wire: Int) { INVOKED(1), TRIGGER_CHARACTER(2), INCOMPLETE_RETRIGGER(3) }
