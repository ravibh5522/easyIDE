package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.bool
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonElement

data class Hover(val contents: Markup, val range: Range?) {
    companion object {
        /** Null for a `null` result or empty contents (pyright answers `""` off-symbol). */
        fun fromJson(e: JsonElement?): Hover? {
            val o = e.obj ?: return null
            val contents = Markup.fromJson(o["contents"])?.takeIf { it.value.isNotBlank() } ?: return null
            return Hover(contents, Range.fromJson(o["range"]))
        }
    }
}

/** A parameter's span inside its signature label, as UTF-16 offsets `[start, end)`. */
data class ParameterInformation(val labelStart: Int, val labelEnd: Int, val documentation: Markup?)

data class SignatureInformation(
    val label: String,
    val documentation: Markup?,
    val parameters: List<ParameterInformation>,
    val activeParameter: Int?,
)

/**
 * `textDocument/signatureHelp` result. [activeParameter] is resolved per lsp-features.md 4.4:
 * the active signature's own `activeParameter` wins over the top-level one.
 */
data class SignatureHelp(val signatures: List<SignatureInformation>, val activeSignature: Int, val activeParameter: Int?) {
    val active: SignatureInformation? get() = signatures.getOrNull(activeSignature)

    companion object {
        fun fromJson(e: JsonElement?): SignatureHelp? {
            val o = e.obj ?: return null
            val signatures = o["signatures"].mapItems(::signatureFromJson)
            if (signatures.isEmpty()) return null
            val index = (o["activeSignature"].int ?: 0).takeIf { it in signatures.indices } ?: 0
            return SignatureHelp(signatures, index, signatures[index].activeParameter ?: o["activeParameter"].int)
        }

        private fun signatureFromJson(e: JsonElement): SignatureInformation? {
            val o = e.obj ?: return null
            val label = o["label"].str ?: return null
            var searchFrom = 0
            val params = o["parameters"].mapItems { p ->
                val po = p.obj ?: return@mapItems null
                val raw = po["label"]
                val span = raw.arr?.let { offsets ->
                    val start = offsets.getOrNull(0).int ?: return@mapItems null
                    val end = offsets.getOrNull(1).int ?: return@mapItems null
                    if (start < 0 || end < start || end > label.length) return@mapItems null
                    start to end
                } ?: raw.str?.let { text ->
                    // String labels are substrings; search after the previous one so `(a, a)` works.
                    val at = label.indexOf(text, searchFrom).takeIf { it >= 0 } ?: return@mapItems null
                    at to at + text.length
                } ?: return@mapItems null
                searchFrom = span.second
                ParameterInformation(span.first, span.second, Markup.fromJson(po["documentation"]))
            }
            return SignatureInformation(label, Markup.fromJson(o["documentation"]), params, o["activeParameter"].int)
        }
    }
}

enum class DocumentHighlightKind(val wire: Int) {
    TEXT(1), READ(2), WRITE(3);

    companion object {
        fun fromWire(v: Int?): DocumentHighlightKind = entries.firstOrNull { it.wire == v } ?: TEXT
    }
}

data class DocumentHighlight(val range: Range, val kind: DocumentHighlightKind) {
    companion object {
        fun listFromJson(e: JsonElement?): List<DocumentHighlight> = e.mapItems { h ->
            val o = h.obj ?: return@mapItems null
            DocumentHighlight(Range.fromJson(o["range"]) ?: return@mapItems null, DocumentHighlightKind.fromWire(o["kind"].int))
        }
    }
}

/** LSP `SymbolKind` 1..26. */
enum class SymbolKind(val wire: Int) {
    FILE(1), MODULE(2), NAMESPACE(3), PACKAGE(4), CLASS(5), METHOD(6), PROPERTY(7), FIELD(8),
    CONSTRUCTOR(9), ENUM(10), INTERFACE(11), FUNCTION(12), VARIABLE(13), CONSTANT(14), STRING(15),
    NUMBER(16), BOOLEAN(17), ARRAY(18), OBJECT(19), KEY(20), NULL(21), ENUM_MEMBER(22), STRUCT(23),
    EVENT(24), OPERATOR(25), TYPE_PARAMETER(26);

    companion object {
        /** Unknown kinds (newer servers) fall back to [VARIABLE] instead of dropping the symbol. */
        fun fromWire(v: Int?): SymbolKind = entries.firstOrNull { it.wire == v } ?: VARIABLE
    }
}

private const val SYMBOL_TAG_DEPRECATED = 1

/** Outline node: from hierarchical `DocumentSymbol[]`, or a flat list rebuilt by containment. */
data class SymbolNode(
    val name: String,
    val detail: String?,
    val kind: SymbolKind,
    val deprecated: Boolean,
    val range: Range,
    val selectionRange: Range,
    val children: List<SymbolNode>,
) {
    companion object {
        /** Parses `DocumentSymbol[] | SymbolInformation[] | null`. */
        fun listFromJson(e: JsonElement?): List<SymbolNode> {
            val items = e.arr ?: return emptyList()
            val hierarchical = items.firstOrNull()?.obj?.containsKey("selectionRange") ?: return emptyList()
            return if (hierarchical) items.mapNotNull(::nodeFromJson) else treeFromFlat(items.mapNotNull(::flatFromJson))
        }

        private fun nodeFromJson(e: JsonElement): SymbolNode? {
            val o = e.obj ?: return null
            val range = Range.fromJson(o["range"]) ?: return null
            return SymbolNode(
                name = o["name"].str ?: return null,
                detail = o["detail"].str,
                kind = SymbolKind.fromWire(o["kind"].int),
                deprecated = o["deprecated"].bool == true || o["tags"].mapItems { it.int }.contains(SYMBOL_TAG_DEPRECATED),
                range = range,
                selectionRange = Range.fromJson(o["selectionRange"]) ?: range,
                children = o["children"].mapItems(::nodeFromJson),
            )
        }

        private fun flatFromJson(e: JsonElement): SymbolNode? {
            val o = e.obj ?: return null
            val location = Location.fromJson(o["location"]) ?: return null
            return SymbolNode(
                name = o["name"].str ?: return null,
                detail = o["containerName"].str,
                kind = SymbolKind.fromWire(o["kind"].int),
                deprecated = o["deprecated"].bool == true || o["tags"].mapItems { it.int }.contains(SYMBOL_TAG_DEPRECATED),
                range = location.range,
                selectionRange = location.range,
                children = emptyList(),
            )
        }

        /**
         * Nests flat symbols: each becomes a child of the innermost earlier symbol whose range
         * contains it. Sorted by start (then widest first) so parents precede children.
         */
        private fun treeFromFlat(flat: List<SymbolNode>): List<SymbolNode> {
            class Building(val node: SymbolNode, val kids: MutableList<Building> = mutableListOf()) {
                fun build(): SymbolNode = node.copy(children = kids.map { it.build() })
            }
            val sorted = flat.sortedWith(compareBy<SymbolNode> { it.range.start }.thenByDescending { it.range.end })
            val roots = mutableListOf<Building>()
            val stack = ArrayDeque<Building>()
            for (node in sorted) {
                while (stack.isNotEmpty() && !stack.last().node.range.encloses(node.range)) stack.removeLast()
                val b = Building(node)
                if (stack.isEmpty()) roots += b else stack.last().kids += b
                stack.addLast(b)
            }
            return roots.map { it.build() }
        }

        private fun Range.encloses(other: Range): Boolean = start <= other.start && other.end <= end
    }
}

/** `workspace/symbol` result item (flat `SymbolInformation` or `WorkspaceSymbol` with a full location). */
data class WorkspaceSymbolItem(val name: String, val kind: SymbolKind, val containerName: String?, val location: Location) {
    companion object {
        fun listFromJson(e: JsonElement?): List<WorkspaceSymbolItem> = e.mapItems { s ->
            val o = s.obj ?: return@mapItems null
            WorkspaceSymbolItem(
                name = o["name"].str ?: return@mapItems null,
                kind = SymbolKind.fromWire(o["kind"].int),
                containerName = o["containerName"].str,
                // A location without a range needs `workspaceSymbol/resolve`, which is not advertised.
                location = Location.fromJson(o["location"]) ?: return@mapItems null,
            )
        }
    }
}
