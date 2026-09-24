package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.putOpt
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Hand-rolled LSP 3.17 base types (ADR 0017). Readers are total: a malformed value gives
 * null and the caller drops that one item. Unknown fields are ignored; optional fields are
 * absent on write, never null.
 */

/** Zero-based line and UTF-16 code-unit column (the only encoding the client offers). */
data class Position(val line: Int, val character: Int) : Comparable<Position> {
    override fun compareTo(other: Position): Int =
        if (line != other.line) line.compareTo(other.line) else character.compareTo(other.character)

    fun toJson(): JsonObject = buildJsonObject {
        put("line", JsonPrimitive(line))
        put("character", JsonPrimitive(character))
    }

    companion object {
        fun fromJson(e: JsonElement?): Position? {
            val o = e.obj ?: return null
            val line = o["line"].int?.takeIf { it >= 0 } ?: return null
            val character = o["character"].int?.takeIf { it >= 0 } ?: return null
            return Position(line, character)
        }
    }
}

/** Half-open `[start, end)`; a reversed range from a server is normalised on read. */
data class Range(val start: Position, val end: Position) {
    fun toJson(): JsonObject = buildJsonObject {
        put("start", start.toJson())
        put("end", end.toJson())
    }

    operator fun contains(p: Position): Boolean = p >= start && p < end

    companion object {
        fun fromJson(e: JsonElement?): Range? {
            val o = e.obj ?: return null
            val a = Position.fromJson(o["start"]) ?: return null
            val b = Position.fromJson(o["end"]) ?: return null
            return if (a <= b) Range(a, b) else Range(b, a)
        }

        fun at(p: Position): Range = Range(p, p)
    }
}

data class Location(val uri: String, val range: Range) {
    fun toJson(): JsonObject = buildJsonObject {
        put("uri", JsonPrimitive(uri))
        put("range", range.toJson())
    }

    companion object {
        fun fromJson(e: JsonElement?): Location? {
            val o = e.obj ?: return null
            return Location(o["uri"].str ?: return null, Range.fromJson(o["range"]) ?: return null)
        }
    }
}

/**
 * A navigation target, normalised from `Location` or `LocationLink` (lsp-features.md 4.5).
 * [range] is where to reveal (the link's `targetSelectionRange`), [fullRange] the whole
 * target, [originSelectionRange] what was clicked when the server says so.
 */
data class NavTarget(
    val uri: String,
    val range: Range,
    val fullRange: Range,
    val originSelectionRange: Range?,
) {
    companion object {
        /** Parses `Location | Location[] | LocationLink[] | null`. */
        fun listFromJson(e: JsonElement?): List<NavTarget> {
            val items = e.arr ?: e.obj?.let { JsonArray(listOf(it)) } ?: return emptyList()
            return items.mapNotNull(::oneFromJson)
        }

        private fun oneFromJson(e: JsonElement): NavTarget? {
            val o = e.obj ?: return null
            o["targetUri"].str?.let { uri ->
                val full = Range.fromJson(o["targetRange"]) ?: return null
                val selection = Range.fromJson(o["targetSelectionRange"]) ?: full
                return NavTarget(uri, selection, full, Range.fromJson(o["originSelectionRange"]))
            }
            val loc = Location.fromJson(o) ?: return null
            return NavTarget(loc.uri, loc.range, loc.range, null)
        }
    }
}

data class TextEdit(val range: Range, val newText: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("range", range.toJson())
        put("newText", JsonPrimitive(newText))
    }

    companion object {
        fun fromJson(e: JsonElement?): TextEdit? {
            val o = e.obj ?: return null
            return TextEdit(Range.fromJson(o["range"]) ?: return null, o["newText"].str ?: return null)
        }

        fun listFromJson(e: JsonElement?): List<TextEdit> = e.mapItems(::fromJson)
    }
}

/** A server command; [arguments] are opaque and go back verbatim to `workspace/executeCommand`. */
data class Command(val title: String, val command: String, val arguments: JsonArray?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("command", JsonPrimitive(command))
        putOpt("arguments", arguments)
    }

    companion object {
        fun fromJson(e: JsonElement?): Command? {
            val o = e.obj ?: return null
            return Command(o["title"].str.orEmpty(), o["command"].str ?: return null, o["arguments"].arr)
        }
    }
}

enum class MarkupKind(val wire: String) { PLAINTEXT("plaintext"), MARKDOWN("markdown") }

/**
 * Documentation text. `MarkupContent`, a bare string and legacy `MarkedString` /
 * `MarkedString[]` all normalise to this, so renderers handle one shape.
 */
data class Markup(val kind: MarkupKind, val value: String) {
    companion object {
        fun fromJson(e: JsonElement?): Markup? {
            e.str?.let { return Markup(MarkupKind.PLAINTEXT, it) }
            e.arr?.let { parts ->
                val pieces = parts.mapNotNull(::fromJson)
                if (pieces.isEmpty()) return null
                if (pieces.size == 1) return pieces[0]
                // Joined as markdown: a MarkedString array mixes prose and code blocks.
                return Markup(MarkupKind.MARKDOWN, pieces.joinToString(PART_SEPARATOR) { it.asMarkdown() })
            }
            val o = e.obj ?: return null
            o["kind"].str?.let { kind ->
                val k = if (kind == MarkupKind.MARKDOWN.wire) MarkupKind.MARKDOWN else MarkupKind.PLAINTEXT
                return Markup(k, o["value"].str ?: return null)
            }
            // Legacy MarkedString { language, value }: a code block.
            val value = o["value"].str ?: return null
            val language = o["language"].str.orEmpty()
            return Markup(MarkupKind.MARKDOWN, "```$language\n$value\n```")
        }

        private const val PART_SEPARATOR = "\n\n---\n\n"
    }

    fun asMarkdown(): String = if (kind == MarkupKind.MARKDOWN) value else escapeMarkdown(value)

    private fun escapeMarkdown(text: String): String = text.replace(MARKDOWN_SPECIAL) { "\\" + it.value }
}

private val MARKDOWN_SPECIAL = Regex("""[\\`*_{}\[\]()#+\-.!|<>]""")

data class TextDocumentIdentifier(val uri: String) {
    fun toJson(): JsonObject = buildJsonObject { put("uri", JsonPrimitive(uri)) }
}

/** `{textDocument, position}` - the params of most position-based requests. */
fun positionParams(uri: String, position: Position): JsonObject = buildJsonObject {
    put("textDocument", TextDocumentIdentifier(uri).toJson())
    put("position", position.toJson())
}

fun documentParams(uri: String): JsonObject = buildJsonObject {
    put("textDocument", TextDocumentIdentifier(uri).toJson())
}
