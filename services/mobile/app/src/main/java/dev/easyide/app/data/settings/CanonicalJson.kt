package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

/**
 * RFC 8785 JSON Canonicalization Scheme: the same bytes for the same data no
 * matter how a file orders keys or spells numbers. Project trust fingerprints
 * with it, so reformatting a trusted `settings.json` does not re-prompt while
 * any change to what would be executed does.
 */
object CanonicalJson {

    fun encode(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

    /** Lower-case hex sha256 of the canonical UTF-8 bytes. */
    fun sha256(value: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(encode(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                // RFC 8785 sorts by UTF-16 code units, which is String.compareTo.
                value.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    string(key, out)
                    out.append(':')
                    write(value.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                value.isString -> string(value.content, out)
                value.content == "true" || value.content == "false" -> out.append(value.content)
                else -> out.append(number(value.content.toDouble()))
            }
        }
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /**
     * ECMAScript Number.prototype.toString, as RFC 8785 requires. The shortest
     * round-tripping digit string is searched explicitly because
     * Double.toString is not guaranteed shortest on every Android runtime.
     */
    internal fun number(d: Double): String {
        require(!d.isNaN() && !d.isInfinite()) { "not a JSON number" }
        if (d == 0.0) return "0"
        if (d < 0) return "-" + number(-d)
        val (digits, exp) = shortestDigits(d)
        val k = digits.length
        val n = exp + 1 // position of the decimal point relative to the digit string
        return when {
            n in k..MAX_PLAIN_EXPONENT -> digits + "0".repeat(n - k)
            n in 1..MAX_PLAIN_EXPONENT -> digits.substring(0, n) + "." + digits.substring(n)
            n in (MIN_PLAIN_EXPONENT + 1)..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
            }
        }
    }

    /** Significant digits without trailing zeros and the decimal exponent of the first one. */
    private fun shortestDigits(d: Double): Pair<String, Int> {
        for (precision in 1..MAX_DOUBLE_DIGITS) {
            val sci = String.format(Locale.ROOT, "%.${precision - 1}e", d)
            if (sci.toDouble() != d) continue
            val bd = BigDecimal(sci)
            val digits = bd.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val exp = bd.precision() - bd.scale() - 1
            return digits to exp
        }
        error("unreachable: 17 digits always round-trip a double")
    }

    private const val MAX_DOUBLE_DIGITS = 17
    private const val MAX_PLAIN_EXPONENT = 21
    private const val MIN_PLAIN_EXPONENT = -6
}
