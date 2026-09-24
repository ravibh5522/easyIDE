package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import dev.easyide.lsp.json.strings
import kotlinx.serialization.json.JsonElement

/** The server's `SemanticTokensLegend`: indices in the int stream refer into these lists. */
data class SemanticTokensLegend(val tokenTypes: List<String>, val tokenModifiers: List<String>) {
    companion object {
        fun fromJson(e: JsonElement?): SemanticTokensLegend? {
            val o = e.obj ?: return null
            return SemanticTokensLegend(o["tokenTypes"].strings, o["tokenModifiers"].strings)
        }
    }
}

/** A full `semanticTokens` result: the raw relative-encoded stream, kept for later deltas. */
class SemanticTokensData(val resultId: String?, val data: IntArray) {
    companion object {
        fun fromJson(e: JsonElement?): SemanticTokensData? {
            val o = e.obj ?: return null
            return SemanticTokensData(o["resultId"].str, intArray(o["data"]) ?: return null)
        }
    }
}

/** One `SemanticTokensEdit`: replace [deleteCount] ints at [start] with [data]. */
class SemanticTokensEdit(val start: Int, val deleteCount: Int, val data: IntArray)

/** A `full/delta` result is either a delta or, when the server chose to, a new full set. */
sealed interface SemanticTokensDeltaResult {
    class Delta(val resultId: String?, val edits: List<SemanticTokensEdit>) : SemanticTokensDeltaResult
    class Full(val tokens: SemanticTokensData) : SemanticTokensDeltaResult

    companion object {
        fun fromJson(e: JsonElement?): SemanticTokensDeltaResult? {
            val o = e.obj ?: return null
            if (o.containsKey("data")) return SemanticTokensData.fromJson(o)?.let(::Full)
            val edits = o["edits"].mapItems { ed ->
                val eo = ed.obj ?: return@mapItems null
                SemanticTokensEdit(
                    eo["start"].int ?: return@mapItems null,
                    eo["deleteCount"].int ?: return@mapItems null,
                    eo["data"]?.let { intArray(it) ?: return@mapItems null } ?: IntArray(0),
                )
            }
            return Delta(o["resultId"].str, edits)
        }
    }
}

/** One decoded token: absolute line, UTF-16 start column and length on that line. */
data class SemanticToken(val line: Int, val start: Int, val length: Int, val type: String, val modifiers: Set<String>)

object SemanticTokensCodec {
    private const val INTS_PER_TOKEN = 5

    /**
     * Applies delta edits to the previous stream in one pass. Edits address the previous
     * array (not each other's output), so they are sorted by start and spliced in order.
     *
     * @return null if edits overlap or fall outside the previous data; the caller then asks
     *   for a full set.
     */
    fun applyEdits(previous: IntArray, edits: List<SemanticTokensEdit>): IntArray? {
        val sorted = edits.sortedBy { it.start }
        var size = previous.size
        var lastEnd = 0
        for (e in sorted) {
            if (e.start < lastEnd || e.deleteCount < 0 || e.start + e.deleteCount > previous.size) return null
            lastEnd = e.start + e.deleteCount
            size += e.data.size - e.deleteCount
        }
        val out = IntArray(size)
        var src = 0
        var dst = 0
        for (e in sorted) {
            previous.copyInto(out, dst, src, e.start)
            dst += e.start - src
            e.data.copyInto(out, dst)
            dst += e.data.size
            src = e.start + e.deleteCount
        }
        previous.copyInto(out, dst, src, previous.size)
        return out
    }

    /**
     * Decodes the relative 5-int encoding (deltaLine, deltaStart, length, type, modifiers)
     * against [legend]. Tokens with an unknown type index are skipped; a trailing partial
     * group is ignored.
     */
    fun decode(data: IntArray, legend: SemanticTokensLegend): List<SemanticToken> {
        val out = ArrayList<SemanticToken>(data.size / INTS_PER_TOKEN)
        var line = 0
        var start = 0
        var i = 0
        while (i + INTS_PER_TOKEN <= data.size) {
            val deltaLine = data[i]
            line += deltaLine
            start = if (deltaLine == 0) start + data[i + 1] else data[i + 1]
            val type = legend.tokenTypes.getOrNull(data[i + 3])
            if (type != null && data[i + 2] > 0) {
                out += SemanticToken(line, start, data[i + 2], type, modifiers(data[i + 4], legend))
            }
            i += INTS_PER_TOKEN
        }
        return out
    }

    private fun modifiers(bits: Int, legend: SemanticTokensLegend): Set<String> {
        if (bits == 0) return emptySet()
        val out = LinkedHashSet<String>()
        legend.tokenModifiers.forEachIndexed { index, name -> if (bits and (1 shl index) != 0) out += name }
        return out
    }
}

private fun intArray(e: JsonElement?): IntArray? {
    val a = e.arr ?: return null
    val out = IntArray(a.size)
    for (i in a.indices) out[i] = a[i].int ?: return null
    return out
}
