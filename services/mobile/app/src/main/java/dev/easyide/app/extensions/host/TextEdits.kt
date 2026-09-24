package dev.easyide.app.extensions.host

import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TextPosition
import dev.easyide.extensions.action.TextRange
import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URISyntaxException

/** Pure text-edit arithmetic for `applyEdit` (LSP 0-based line/character positions). */
object TextEdits {

    /**
     * [text] with every edit applied, or null when a range falls outside the text or two
     * edits overlap (LSP requires non-overlapping edits; applying half would corrupt).
     * Edits are applied from the end so earlier offsets stay valid.
     */
    fun apply(text: String, edits: List<Pair<TextRange, String>>): String? {
        val starts = lineStarts(text)
        val spans = edits.map { (range, replacement) ->
            val from = offsetOf(text, starts, range.start) ?: return null
            val to = offsetOf(text, starts, range.end) ?: return null
            if (to < from) return null
            Triple(from, to, replacement)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        for (i in 1 until spans.size) if (spans[i].first < spans[i - 1].second) return null
        val sb = StringBuilder(text)
        for ((from, to, replacement) in spans.asReversed()) sb.replace(from, to, replacement)
        return sb.toString()
    }

    /** Character offset of a 0-based [pos]; a character past the line end clamps to it, as LSP says. */
    fun offsetOf(text: String, starts: IntArray, pos: TextPosition): Int? {
        if (pos.line < 0 || pos.character < 0 || pos.line >= starts.size) return null
        val lineStart = starts[pos.line]
        val lineEnd = if (pos.line + 1 < starts.size) starts[pos.line + 1] - 1 else text.length
        return (lineStart + pos.character).coerceAtMost(lineEnd)
    }

    fun lineStarts(text: String): IntArray {
        val out = ArrayList<Int>()
        out += 0
        text.forEachIndexed { i, c -> if (c == '\n') out += i + 1 }
        return out.toIntArray()
    }

    /** 0-based offset -> 1-based line and column, for [dev.easyide.extensions.action.EditorState]. */
    fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', clamped - 1) + 1
        val line = text.substring(0, clamped).count { it == '\n' }
        return (line + 1) to (clamped - lineStart + 1)
    }

    /**
     * The text edits of an LSP `WorkspaceEdit` (`changes` or `documentChanges`
     * `TextDocumentEdit`s) as guest-path edits, or null when it holds resource operations
     * (create/rename/delete file) or malformed entries: those are refused whole rather
     * than half-applied.
     */
    fun fromWorkspaceEdit(edit: JsonObject): List<ResolvedTextEdit>? {
        val out = ArrayList<ResolvedTextEdit>()
        (edit[CHANGES] as? JsonObject)?.forEach { (uri, list) ->
            val path = pathOf(uri) ?: return null
            out += textEdits(path, list as? JsonArray ?: return null) ?: return null
        }
        (edit[DOCUMENT_CHANGES] as? JsonArray)?.forEach { change ->
            val o = change as? JsonObject ?: return null
            if (o[KIND] != null) return null
            val uri = (o[TEXT_DOCUMENT] as? JsonObject)?.get(URI_KEY)?.stringOrNull ?: return null
            val path = pathOf(uri) ?: return null
            out += textEdits(path, o[EDITS] as? JsonArray ?: return null) ?: return null
        }
        return out
    }

    private fun textEdits(path: String, list: JsonArray): List<ResolvedTextEdit>? = list.map { e ->
        val o = e as? JsonObject ?: return null
        val range = o[RANGE] as? JsonObject ?: return null
        ResolvedTextEdit(
            path,
            TextRange(position(range[START]) ?: return null, position(range[END]) ?: return null),
            o[NEW_TEXT]?.stringOrNull ?: return null,
        )
    }

    private fun position(e: kotlinx.serialization.json.JsonElement?): TextPosition? {
        val o = e as? JsonObject ?: return null
        return TextPosition(o[LINE]?.intOrNull ?: return null, o[CHARACTER]?.intOrNull ?: return null)
    }

    private fun pathOf(uri: String): String? {
        val parsed = try { URI(uri) } catch (e: URISyntaxException) { return null }
        return parsed.path?.takeIf { parsed.scheme.equals(FILE_SCHEME, ignoreCase = true) }
    }

    private const val CHANGES = "changes"
    private const val DOCUMENT_CHANGES = "documentChanges"
    private const val KIND = "kind"
    private const val TEXT_DOCUMENT = "textDocument"
    private const val URI_KEY = "uri"
    private const val EDITS = "edits"
    private const val RANGE = "range"
    private const val START = "start"
    private const val END = "end"
    private const val LINE = "line"
    private const val CHARACTER = "character"
    private const val NEW_TEXT = "newText"
    private const val FILE_SCHEME = "file"
}
