package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonPrimitive

/**
 * One completion: replace `[start, end)` of the buffer with [insert]. [label] is what the
 * list shows, [detail] the setting's kind.
 */
data class JsonSuggestion(val label: String, val detail: String?, val start: Int, val end: Int, val insert: String) {
    fun applyTo(text: String): Pair<String, Int> = text.replaceRange(start, end, insert) to start + insert.length
}

/**
 * Key and value completion for the settings / keybindings JSON editor (LLD sec 16 "key/enum
 * completion from the schema"). Pure and tolerant: it lexes the text up to the cursor
 * (strings, JSONC comments, punctuation) to learn whether the cursor is at a key or a value
 * and inside which object, so a half-typed buffer that does not parse still gets help.
 *
 * - top-level keys: every schema key; inside a `"[lang]"` block only L-scope keys;
 * - values: enum names, contributed `enum` choices and booleans of the key before the colon;
 * - keybindings.json: `command` values from [commands].
 */
object SettingsJsonCompletion {

    enum class Kind { SETTINGS, KEYBINDINGS }

    private const val MAX = 8

    private sealed interface Tok {
        data class Str(val text: String) : Tok
        data class Punct(val c: Char) : Tok
        data object Other : Tok
    }

    /** What the lexer saw before the cursor. */
    private class Scan(
        val tokens: List<Tok>,
        /** Offset of the opening quote when the cursor is inside a string, else null. */
        val openString: Int?,
        val partial: String,
        /** Per open container: `{` or `[`, and for objects the key that opened it (null at the root). */
        val stack: List<Pair<Char, String?>>,
    )

    fun suggest(text: String, cursor: Int, kind: Kind, schema: SchemaState, commands: Collection<String> = emptyList()): List<JsonSuggestion> {
        val at = cursor.coerceIn(0, text.length)
        val scan = scan(text, at) ?: return emptyList()
        val last = scan.tokens.lastOrNull()
        val closesQuote = at < text.length && text[at] == '"'
        val top = scan.stack.lastOrNull() ?: return emptyList()
        val keyPosition = top.first == '{' && (last == Tok.Punct('{') || last == Tok.Punct(','))
        val valuePosition = last == Tok.Punct(':')
        return when {
            keyPosition && kind == Kind.SETTINGS -> {
                val keys = keysFor(scan.stack, schema) ?: return emptyList()
                val word = if (scan.openString == null) wordBefore(text, at) else ""
                rank(keys.map { it.key }, if (scan.openString != null) scan.partial else word).map { key ->
                    val detail = schema.byKey[key]?.let(::kindOf)
                    if (scan.openString != null) {
                        JsonSuggestion(key, detail, scan.openString + 1, if (closesQuote) at + 1 else at, "$key\": ")
                    } else {
                        JsonSuggestion(key, detail, at - word.length, at, "\"$key\": ")
                    }
                }
            }
            valuePosition -> {
                val key = (scan.tokens.getOrNull(scan.tokens.size - 2) as? Tok.Str)?.text ?: return emptyList()
                val values = when (kind) {
                    Kind.SETTINGS -> if (keysFor(scan.stack, schema)?.any { it.key == key } == true) valuesOf(schema.byKey[key]) else emptyList()
                    Kind.KEYBINDINGS -> if (key == "command" && top.first == '{') commands.sorted().map { JsonPrimitive(it) } else emptyList()
                }
                val strings = values.filter { it.isString }.map { it.content }
                val literals = values.filterNot { it.isString }.map { it.content }
                if (scan.openString != null) {
                    rank(strings, scan.partial).map { v -> JsonSuggestion(v, null, scan.openString + 1, if (closesQuote) at + 1 else at, "$v\"") }
                } else {
                    val word = wordBefore(text, at)
                    (rank(literals, word) + rank(strings, word)).take(MAX).map { v ->
                        val insert = if (v in literals) v else "\"$v\""
                        JsonSuggestion(v, null, at - word.length, at, insert)
                    }
                }
            }
            else -> emptyList()
        }
    }

    /** Keys allowed in the innermost object: root = all, `"[lang]"` block = L scope, anything deeper = none. */
    private fun keysFor(stack: List<Pair<Char, String?>>, schema: SchemaState): List<Setting<*>>? = when {
        stack.size == 1 && stack[0].first == '{' -> schema.settings
        stack.size == 2 && stack[1].first == '{' && stack[1].second?.let(LayerDoc::isLanguageKey) == true ->
            schema.settings.filter { it.scope.languageOverridable }
        else -> null
    }

    private fun valuesOf(s: Setting<*>?): List<JsonPrimitive> = when (s) {
        is Setting.Enum<*> -> s.ids.map(::JsonPrimitive)
        is Setting.Bool -> listOf(JsonPrimitive(true), JsonPrimitive(false))
        is Setting.Contributed -> when (val c = s.control) {
            is ContributedControl.Choice -> c.values.map(::JsonPrimitive)
            ContributedControl.Switch -> listOf(JsonPrimitive(true), JsonPrimitive(false))
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun kindOf(s: Setting<*>): String = when (s) {
        is Setting.Bool -> "boolean"
        is Setting.IntRange -> "${s.min}..${s.max}"
        is Setting.Decimal -> "${s.min}..${s.max}"
        is Setting.Enum<*> -> "enum"
        is Setting.Str -> "string"
        is Setting.StrList -> "string[]"
        is Setting.Json -> "object"
        is Setting.Contributed -> s.owner
    }

    /** Prefix matches first, then substring matches; case-insensitive; the exact text itself is dropped. */
    private fun rank(options: List<String>, typed: String): List<String> {
        val starts = options.filter { it.startsWith(typed, ignoreCase = true) && it != typed }
        val contains = if (typed.isEmpty()) emptyList() else options.filter { it.contains(typed, ignoreCase = true) && it !in starts && it != typed }
        return (starts + contains).take(MAX)
    }

    /** The literal being typed before [at] (letters, digits, `.`, `_`, `-`). */
    private fun wordBefore(text: String, at: Int): String {
        var i = at
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] in "._-")) i--
        return text.substring(i, at)
    }

    /** Lexes `[0, end)`; null when the cursor is in a comment. */
    private fun scan(text: String, end: Int): Scan? {
        val tokens = ArrayList<Tok>()
        val stack = ArrayList<Pair<Char, String?>>()
        var i = 0
        while (i < end) {
            val c = text[i]
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < end && text[j] != '"') {
                        if (text[j] == '\\' && j + 1 < end) { sb.append(text[j + 1]); j += 2 } else { sb.append(text[j]); j++ }
                    }
                    if (j >= end) return Scan(tokens, i, sb.toString(), stack)
                    tokens += Tok.Str(sb.toString())
                    i = j + 1
                    continue
                }
                c == '/' && i + 1 < end && text[i + 1] == '/' -> {
                    val nl = text.indexOf('\n', i)
                    if (nl < 0 || nl >= end) return null
                    i = nl + 1
                    continue
                }
                c == '/' && i + 1 < end && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0 || close + 2 > end) return null
                    i = close + 2
                    continue
                }
                c == '{' || c == '[' -> {
                    // The key that opened this object: `"key": {`.
                    val key = if (tokens.lastOrNull() == Tok.Punct(':')) (tokens.getOrNull(tokens.size - 2) as? Tok.Str)?.text else null
                    stack += c to key
                    tokens += Tok.Punct(c)
                }
                c == '}' || c == ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); tokens += Tok.Punct(c) }
                c == ':' || c == ',' -> tokens += Tok.Punct(c)
                c.isWhitespace() -> Unit
                else -> {
                    // A literal (number, true, ...): one token for the whole run.
                    var j = i
                    while (j < end && !text[j].isWhitespace() && text[j] !in "{}[]:,\"") j++
                    if (j >= end) return Scan(tokens, null, "", stack)
                    tokens += Tok.Other
                    i = j
                    continue
                }
            }
            i++
        }
        return Scan(tokens, null, "", stack)
    }
}
