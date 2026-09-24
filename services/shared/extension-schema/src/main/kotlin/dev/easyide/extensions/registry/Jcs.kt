package dev.easyide.extensions.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** A signed document broke the canonical-JSON rules; the whole document is invalid. */
class CanonicalJsonException(message: String) : IllegalArgumentException(message)

/**
 * RFC 8785 canonical JSON for signed registry documents (registry-and-install.md sec 4.1).
 * Signed JSON holds integers in +-(2^53-1) only, so ES6 double formatting is out of scope:
 * any other number makes the document invalid. Shared by the app and `easyide-ext` so both
 * sign and verify the same bytes.
 */
object Jcs {
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** Canonical UTF-8 bytes of [value]. @throws CanonicalJsonException on a non-integer number. */
    fun canonicalize(value: JsonElement): ByteArray = buildString { write(value, this) }.toByteArray(Charsets.UTF_8)

    /** Strict parse: duplicate keys and non-integer numbers are refused (kotlinx keeps the last duplicate). */
    fun parse(text: String): JsonElement = StrictJsonReader(text).document()

    private fun write(v: JsonElement, out: StringBuilder) {
        when (v) {
            is JsonObject -> {
                out.append('{')
                // UTF-16 code unit order is String's natural order.
                v.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out)
                    out.append(':')
                    write(v.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                v.forEachIndexed { i, e -> if (i > 0) out.append(','); write(e, out) }
                out.append(']')
            }
            JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                v.isString -> string(v.content, out)
                v.content == "true" || v.content == "false" -> out.append(v.content)
                else -> out.append(integer(v.content))
            }
        }
    }

    private fun integer(literal: String): Long {
        val n = try { BigDecimal(literal) } catch (e: NumberFormatException) { throw CanonicalJsonException("not a number: $literal") }
        val exact = try { n.toBigIntegerExact() } catch (e: ArithmeticException) { throw CanonicalJsonException("non-integer number: $literal") }
        if (exact.bitLength() > 63 || Math.abs(exact.toLong()) > MAX_SAFE_INTEGER) throw CanonicalJsonException("integer out of range: $literal")
        return exact.toLong()
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\u000C' -> out.append("\\f")
                '\r' -> out.append("\\r")
                else -> if (c < ' ') out.append("\\u").append(String.format("%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}

/** Minimal RFC 8259 reader with the extra refusals signed documents need. */
private class StrictJsonReader(private val s: String) {
    private var i = 0

    fun document(): JsonElement {
        val v = value(0)
        ws()
        if (i != s.length) fail("trailing content")
        return v
    }

    private fun value(depth: Int): JsonElement {
        if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
        ws()
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> JsonPrimitive(str())
            't' -> literal("true", JsonPrimitive(true))
            'f' -> literal("false", JsonPrimitive(false))
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
        }
    }

    private fun obj(depth: Int): JsonObject {
        i++
        val m = LinkedHashMap<String, JsonElement>()
        ws()
        if (peek('}')) { i++; return JsonObject(m) }
        while (true) {
            ws()
            if (!peek('"')) fail("expected key")
            val k = str()
            if (k in m) fail("duplicate key '$k'")
            ws(); expect(':')
            m[k] = value(depth + 1)
            ws()
            if (peek(',')) { i++; continue }
            expect('}')
            return JsonObject(m)
        }
    }

    private fun arr(depth: Int): JsonArray {
        i++
        val l = ArrayList<JsonElement>()
        ws()
        if (peek(']')) { i++; return JsonArray(l) }
        while (true) {
            l += value(depth + 1)
            ws()
            if (peek(',')) { i++; continue }
            expect(']')
            return JsonArray(l)
        }
    }

    private fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return b.toString()
                c < ' ' -> fail("control character in string")
                c == '\\' -> {
                    if (i >= s.length) fail("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                        'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("short \\u escape")
                            b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                else -> b.append(c)
            }
        }
    }

    private fun number(): JsonPrimitive {
        val m = NUMBER.matchAt(s, i) ?: fail("bad number")
        i += m.value.length
        if (m.value.any { it == '.' || it == 'e' || it == 'E' }) fail("non-integer number ${m.value}")
        val n = m.value.toLongOrNull()
        if (n == null || Math.abs(n) > Jcs.MAX_SAFE_INTEGER) fail("integer out of range ${m.value}")
        return JsonPrimitive(n)
    }

    private fun literal(word: String, v: JsonElement): JsonElement {
        if (!s.startsWith(word, i)) fail("unexpected token")
        i += word.length
        return v
    }

    private fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
    private fun peek(c: Char) = i < s.length && s[i] == c
    private fun expect(c: Char) { if (!peek(c)) fail("expected '$c'"); i++ }
    private fun fail(msg: String): Nothing = throw CanonicalJsonException("$msg at offset $i")

    companion object {
        const val MAX_DEPTH = 64
        val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}
