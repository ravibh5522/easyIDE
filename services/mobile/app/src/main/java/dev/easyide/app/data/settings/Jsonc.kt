package dev.easyide.app.data.settings

import androidx.annotation.StringRes
import dev.easyide.app.R
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Why a JSONC text did not parse. */
enum class JsoncError(@StringRes val message: Int) {
    UNEXPECTED_CHARACTER(R.string.jsonc_error_unexpected),
    UNEXPECTED_END(R.string.jsonc_error_end),
    UNTERMINATED_STRING(R.string.jsonc_error_string),
    UNTERMINATED_COMMENT(R.string.jsonc_error_comment),
    INVALID_ESCAPE(R.string.jsonc_error_escape),
    INVALID_NUMBER(R.string.jsonc_error_number),
    EXPECTED_KEY(R.string.jsonc_error_key),
    EXPECTED_COLON(R.string.jsonc_error_colon),
    TRAILING_CONTENT(R.string.jsonc_error_trailing),
    TOO_DEEP(R.string.jsonc_error_depth),
    TOO_LARGE(R.string.jsonc_error_size),
}

/**
 * A parsed value with its source span. Objects keep their members' spans so
 * [JsoncEditor] can rewrite one property without touching comments, and
 * diagnostics can point at a line.
 */
class JsoncNode(
    val value: JsonElement,
    val start: Int,
    val end: Int,
    val members: List<JsoncMember> = emptyList(),
    /** Array elements, for per-entry diagnostics in keybindings.json. */
    val items: List<JsoncNode> = emptyList(),
)

/** [commaAt] is the index of the comma after the value, or -1 when there is none. */
class JsoncMember(val key: String, val keyStart: Int, val node: JsoncNode, val commaAt: Int)

sealed interface JsoncResult {
    data class Ok(val root: JsoncNode) : JsoncResult
    data class Failure(val error: JsoncError, val offset: Int) : JsoncResult
}

/**
 * JSON with comments and trailing commas, as VS Code's `settings.json`. A
 * hand-written parser rather than kotlinx's because settings files come from
 * untrusted places (git clone, sandbox processes): the depth limit must hold
 * before recursion, and edits need exact spans.
 */
object Jsonc {

    fun parse(text: String, maxDepth: Int = SettingsPolicy.MAX_JSON_DEPTH): JsoncResult {
        if (text.length > SettingsPolicy.MAX_FILE_BYTES) return JsoncResult.Failure(JsoncError.TOO_LARGE, 0)
        val parser = Parser(text, maxDepth)
        return try {
            parser.skipTrivia()
            val root = parser.value(0)
            parser.skipTrivia()
            if (parser.pos < text.length) throw ParseError(JsoncError.TRAILING_CONTENT, parser.pos)
            JsoncResult.Ok(root)
        } catch (e: ParseError) {
            JsoncResult.Failure(e.error, e.offset)
        }
    }

    /** 1-based line of [offset], for diagnostics. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    private class ParseError(val error: JsoncError, val offset: Int) : Exception(null, null, false, false)

    private class Parser(private val s: String, private val maxDepth: Int) {
        var pos = 0

        fun skipTrivia() {
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                    c == '/' && peek(1) == '/' -> {
                        while (pos < s.length && s[pos] != '\n') pos++
                    }
                    c == '/' && peek(1) == '*' -> {
                        val end = s.indexOf("*/", pos + 2)
                        if (end < 0) throw ParseError(JsoncError.UNTERMINATED_COMMENT, pos)
                        pos = end + 2
                    }
                    else -> return
                }
            }
        }

        fun value(depth: Int): JsoncNode {
            if (depth > maxDepth) throw ParseError(JsoncError.TOO_DEEP, pos)
            if (pos >= s.length) throw ParseError(JsoncError.UNEXPECTED_END, pos)
            val start = pos
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> JsoncNode(JsonPrimitive(string()), start, pos)
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c.isDigit()) number() else throw ParseError(JsoncError.UNEXPECTED_CHARACTER, pos)
            }
        }

        private fun obj(depth: Int): JsoncNode {
            val start = pos++
            val members = ArrayList<JsoncMember>()
            val map = LinkedHashMap<String, JsonElement>()
            while (true) {
                skipTrivia()
                if (pos >= s.length) throw ParseError(JsoncError.UNEXPECTED_END, pos)
                if (s[pos] == '}') break
                if (s[pos] != '"') throw ParseError(JsoncError.EXPECTED_KEY, pos)
                val keyStart = pos
                val key = string()
                skipTrivia()
                if (pos >= s.length || s[pos] != ':') throw ParseError(JsoncError.EXPECTED_COLON, pos)
                pos++
                skipTrivia()
                val node = value(depth + 1)
                skipTrivia()
                val comma = if (pos < s.length && s[pos] == ',') pos++ else -1
                members += JsoncMember(key, keyStart, node, comma)
                map[key] = node.value
                if (comma < 0) {
                    skipTrivia()
                    if (pos >= s.length) throw ParseError(JsoncError.UNEXPECTED_END, pos)
                    if (s[pos] != '}') throw ParseError(JsoncError.UNEXPECTED_CHARACTER, pos)
                    break
                }
            }
            pos++
            return JsoncNode(JsonObject(map), start, pos, members)
        }

        private fun array(depth: Int): JsoncNode {
            val start = pos++
            val items = ArrayList<JsoncNode>()
            while (true) {
                skipTrivia()
                if (pos >= s.length) throw ParseError(JsoncError.UNEXPECTED_END, pos)
                if (s[pos] == ']') break
                items += value(depth + 1)
                skipTrivia()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                skipTrivia()
                if (pos >= s.length) throw ParseError(JsoncError.UNEXPECTED_END, pos)
                if (s[pos] != ']') throw ParseError(JsoncError.UNEXPECTED_CHARACTER, pos)
                break
            }
            pos++
            return JsoncNode(JsonArray(items.map { it.value }), start, pos, items = items)
        }

        private fun string(): String {
            val start = pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseError(JsoncError.UNTERMINATED_STRING, start)
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escape())
                    c < ' ' -> throw ParseError(JsoncError.UNTERMINATED_STRING, pos - 1)
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (pos >= s.length) throw ParseError(JsoncError.INVALID_ESCAPE, pos)
            return when (val e = s[pos++]) {
                '"', '\\', '/' -> e
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > s.length) throw ParseError(JsoncError.INVALID_ESCAPE, pos)
                    val code = s.substring(pos, pos + 4).toIntOrNull(HEX)
                        ?: throw ParseError(JsoncError.INVALID_ESCAPE, pos)
                    pos += 4
                    code.toChar()
                }
                else -> throw ParseError(JsoncError.INVALID_ESCAPE, pos - 1)
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun number(): JsoncNode {
            val m = NUMBER.matchAt(s, pos) ?: throw ParseError(JsoncError.INVALID_NUMBER, pos)
            val start = pos
            pos = m.range.last + 1
            // Kept as written: canonical JSON and round-trips must not reformat 1.0 as 1.
            return JsoncNode(JsonUnquotedLiteral(m.value), start, pos)
        }

        private fun literal(word: String, value: JsonElement): JsoncNode {
            if (!s.startsWith(word, pos)) throw ParseError(JsoncError.UNEXPECTED_CHARACTER, pos)
            val start = pos
            pos += word.length
            return JsoncNode(value, start, pos)
        }

        private fun peek(ahead: Int): Char? = s.getOrNull(pos + ahead)
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private const val HEX = 16
}
