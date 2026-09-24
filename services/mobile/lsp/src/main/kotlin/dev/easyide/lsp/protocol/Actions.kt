package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.bool
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A code action, or a bare `Command` the server returned in its place (older servers do):
 * then [kind], [edit] are null and [command] is set. [raw] goes back to `codeAction/resolve`.
 */
data class CodeAction(
    val title: String,
    val kind: String?,
    val diagnostics: List<Diagnostic>,
    val isPreferred: Boolean,
    val disabledReason: String?,
    val edit: WorkspaceEdit?,
    val command: Command?,
    val raw: JsonObject,
) {
    /** True when neither edit nor command is present yet: `codeAction/resolve` fills them. */
    val needsResolve: Boolean get() = edit == null && command == null

    companion object {
        fun fromJson(e: JsonElement?): CodeAction? {
            val o = e.obj ?: return null
            val title = o["title"].str ?: return null
            // A Command has a string `command`; a CodeAction's `command` is an object.
            o["command"].str?.let { return CodeAction(title, null, emptyList(), false, null, null, Command.fromJson(o), o) }
            return CodeAction(
                title = title,
                kind = o["kind"].str,
                diagnostics = Diagnostic.listFromJson(o["diagnostics"]),
                isPreferred = o["isPreferred"].bool == true,
                disabledReason = o["disabled"].obj?.get("reason").str,
                edit = WorkspaceEdit.fromJson(o["edit"]),
                command = Command.fromJson(o["command"]),
                raw = o,
            )
        }

        fun listFromJson(e: JsonElement?): List<CodeAction> = e.mapItems(::fromJson)
    }
}

/** `CodeActionTriggerKind`: lightbulb is automatic, the menu is invoked (lsp-features.md 4.10). */
enum class CodeActionTriggerKind(val wire: Int) { INVOKED(1), AUTOMATIC(2) }

data class CodeLens(val range: Range, val command: Command?, val raw: JsonObject) {
    companion object {
        fun fromJson(e: JsonElement?): CodeLens? {
            val o = e.obj ?: return null
            return CodeLens(Range.fromJson(o["range"]) ?: return null, Command.fromJson(o["command"]), o)
        }

        fun listFromJson(e: JsonElement?): List<CodeLens> = e.mapItems(::fromJson)
    }
}

data class DocumentLink(val range: Range, val target: String?, val tooltip: String?, val raw: JsonObject) {
    companion object {
        fun fromJson(e: JsonElement?): DocumentLink? {
            val o = e.obj ?: return null
            return DocumentLink(Range.fromJson(o["range"]) ?: return null, o["target"].str, o["tooltip"].str, o)
        }

        fun listFromJson(e: JsonElement?): List<DocumentLink> = e.mapItems(::fromJson)
    }
}

/** `textDocument/prepareRename` result shapes (lsp-features.md 4.9). */
sealed interface PrepareRename {
    data class At(val range: Range, val placeholder: String?) : PrepareRename

    /** `{defaultBehavior: true}`: rename the identifier at the position, client-side word rules. */
    data object DefaultBehavior : PrepareRename

    companion object {
        /** Null means "cannot rename here". */
        fun fromJson(e: JsonElement?): PrepareRename? {
            val o = e.obj ?: return null
            if (o["defaultBehavior"].bool == true) return DefaultBehavior
            Range.fromJson(o)?.let { return At(it, null) }
            return At(Range.fromJson(o["range"]) ?: return null, o["placeholder"].str)
        }
    }
}

enum class InlayHintKind(val wire: Int) {
    TYPE(1), PARAMETER(2);

    companion object {
        fun fromWire(v: Int?): InlayHintKind? = entries.firstOrNull { it.wire == v }
    }
}

/** One label part; a plain-string label becomes a single part. */
data class InlayHintLabelPart(val value: String, val tooltip: Markup?, val location: Location?, val command: Command?)

data class InlayHint(
    val position: Position,
    val label: List<InlayHintLabelPart>,
    val kind: InlayHintKind?,
    val tooltip: Markup?,
    val paddingLeft: Boolean,
    val paddingRight: Boolean,
    val textEdits: List<TextEdit>,
    val raw: JsonObject,
) {
    val text: String get() = label.joinToString("") { it.value }

    companion object {
        fun fromJson(e: JsonElement?): InlayHint? {
            val o = e.obj ?: return null
            val rawLabel = o["label"]
            val label = rawLabel.str?.let { listOf(InlayHintLabelPart(it, null, null, null)) }
                ?: rawLabel.arr?.mapNotNull { p ->
                    val po = p.obj ?: return@mapNotNull null
                    InlayHintLabelPart(
                        po["value"].str ?: return@mapNotNull null,
                        Markup.fromJson(po["tooltip"]),
                        Location.fromJson(po["location"]),
                        Command.fromJson(po["command"]),
                    )
                }
                ?: return null
            if (label.isEmpty()) return null
            return InlayHint(
                position = Position.fromJson(o["position"]) ?: return null,
                label = label,
                kind = InlayHintKind.fromWire(o["kind"].int),
                tooltip = Markup.fromJson(o["tooltip"]),
                paddingLeft = o["paddingLeft"].bool == true,
                paddingRight = o["paddingRight"].bool == true,
                textEdits = TextEdit.listFromJson(o["textEdits"]),
                raw = o,
            )
        }

        fun listFromJson(e: JsonElement?): List<InlayHint> = e.mapItems(::fromJson)
    }
}

/**
 * Line-based fold (the client advertises `lineFoldingOnly`). [kind] is `comment`, `imports`,
 * `region` or a server-specific string.
 */
data class FoldingRange(val startLine: Int, val endLine: Int, val kind: String?, val collapsedText: String?) {
    companion object {
        /** Drops empty or inverted ranges, which some servers emit for one-line blocks. */
        fun listFromJson(e: JsonElement?): List<FoldingRange> = e.mapItems { f ->
            val o = f.obj ?: return@mapItems null
            val start = o["startLine"].int ?: return@mapItems null
            val end = o["endLine"].int ?: return@mapItems null
            if (start < 0 || end <= start) return@mapItems null
            FoldingRange(start, end, o["kind"].str, o["collapsedText"].str)
        }
    }
}

/** A selection range flattened innermost-first: `[word, call, statement, block, ...]`. */
data class SelectionRangeChain(val ranges: List<Range>) {
    companion object {
        /** One chain per requested position; a malformed entry gives an empty chain in its slot. */
        fun listFromJson(e: JsonElement?): List<SelectionRangeChain> = e.arr?.map { item ->
            val chain = mutableListOf<Range>()
            var node = item.obj
            while (node != null) {
                val r = Range.fromJson(node["range"]) ?: break
                // A parent must contain its child; stop at the first that does not.
                if (chain.isNotEmpty() && !(r.start <= chain.last().start && chain.last().end <= r.end)) break
                chain += r
                node = node["parent"].obj
            }
            SelectionRangeChain(chain)
        } ?: emptyList()
    }
}
