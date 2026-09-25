package dev.easyide.app.extensions.adapters

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

/** How a shape's outline ends and joins; the SVG defaults are butt and miter. */
enum class SvgCap { BUTT, ROUND, SQUARE }
enum class SvgJoin { MITER, ROUND, BEVEL }

/** Kinds of drawable element an icon may contain; anything else in the file is ignored. */
enum class SvgKind { PATH, RECT, CIRCLE, ELLIPSE, LINE, POLYGON, POLYLINE }

/**
 * One drawable element with its style already resolved through the `<g>` ancestors:
 * [transform] is the accumulated 2x3 matrix (a b c d e f), colours are ARGB ints (0 = none,
 * opacity folded into the alpha), [n] the element's numeric attributes in SVG order.
 */
class SvgShape(
    val kind: SvgKind,
    val pathData: String,
    val n: FloatArray,
    val transform: FloatArray,
    val fill: Int,
    val stroke: Int,
    val strokeWidth: Float,
    val cap: SvgCap,
    val join: SvgJoin,
    val evenOdd: Boolean,
)

/** A parsed icon: the view box and its shapes in paint order. */
class SvgIcon(val width: Float, val height: Float, val shapes: List<SvgShape>)

/**
 * The SVG subset icon themes use: `svg` (viewBox), `g` (fill, stroke, stroke-width,
 * stroke-linecap/linejoin, opacity and transform inherited by its children), `path`, `rect`,
 * `circle`, `ellipse`, `line`, `polygon` and `polyline`. Transforms understand `translate`,
 * `scale`, `rotate` and `matrix`; colours are `#rgb`, `#rrggbb`, `#rrggbbaa`, `none`, `white`
 * and `black`. Gradients, filters, text, images, CSS and `use` are not drawn, which keeps an icon
 * theme from reaching anything outside its own shapes.
 */
object SvgParser {

    /** Icon files are tiny; more elements than this is not an icon. */
    const val MAX_SHAPES = 512

    private class Style(
        val fill: Int = OPAQUE_BLACK, val stroke: Int = NONE, val strokeWidth: Float = 1f,
        val cap: SvgCap = SvgCap.BUTT, val join: SvgJoin = SvgJoin.MITER,
        val opacity: Float = 1f, val evenOdd: Boolean = false, val transform: FloatArray = IDENTITY,
    )

    /** I/O boundary: [text] is a file's content, so malformed XML yields null instead of a throw. */
    fun parse(text: String): SvgIcon? = try {
        parseXml(text)
    } catch (e: XmlPullParserException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun parseXml(text: String): SvgIcon? {
        val xml = Xml.newPullParser().apply { setInput(StringReader(text)) }
        val styles = ArrayList<Style>()
        val shapes = ArrayList<SvgShape>()
        var width = 0f
        var height = 0f
        var depth = 0
        var skipUntil = -1
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (skipUntil >= 0) continue
                    val parent = styles.lastOrNull() ?: Style()
                    val style = styleOf(xml, parent)
                    styles += style
                    when (xml.name) {
                        "svg" -> viewBox(xml)?.let { width = it.first; height = it.second }
                        "g" -> Unit
                        else -> {
                            shape(xml, style)?.let(shapes::add)
                            if (xml.name !in DRAWABLE) skipUntil = depth
                        }
                    }
                    if (shapes.size > MAX_SHAPES) return null
                }
                XmlPullParser.END_TAG -> {
                    if (skipUntil == depth) skipUntil = -1
                    if (skipUntil < 0 && styles.isNotEmpty()) styles.removeAt(styles.lastIndex)
                    depth--
                }
            }
        }
        return if (width > 0f && height > 0f) SvgIcon(width, height, shapes) else null
    }

    private fun viewBox(xml: XmlPullParser): Pair<Float, Float>? {
        val v = numbers(xml.getAttributeValue(null, "viewBox") ?: "")
        if (v.size == 4 && v[2] > 0f && v[3] > 0f) return v[2] to v[3]
        val w = xml.getAttributeValue(null, "width")?.let(::length)
        val h = xml.getAttributeValue(null, "height")?.let(::length)
        return if (w != null && h != null && w > 0f && h > 0f) w to h else null
    }

    private fun styleOf(xml: XmlPullParser, p: Style): Style {
        fun attr(name: String) = xml.getAttributeValue(null, name)
        val fill = attr("fill")?.let(::color) ?: p.fill
        val stroke = attr("stroke")?.let(::color) ?: p.stroke
        // Opacity multiplies down the tree; fill-opacity and stroke-opacity apply to their own element only.
        val opacity = p.opacity * (attr("opacity")?.toFloatOrNull() ?: 1f)
        val own = attr("transform")?.let(::transform)?.let { multiply(p.transform, it) } ?: p.transform
        return Style(
            fill = withAlpha(fill, attr("fill-opacity")?.toFloatOrNull() ?: 1f),
            stroke = withAlpha(stroke, attr("stroke-opacity")?.toFloatOrNull() ?: 1f),
            strokeWidth = attr("stroke-width")?.let(::length) ?: p.strokeWidth,
            cap = when (attr("stroke-linecap")) { "round" -> SvgCap.ROUND; "square" -> SvgCap.SQUARE; null -> p.cap; else -> SvgCap.BUTT },
            join = when (attr("stroke-linejoin")) { "round" -> SvgJoin.ROUND; "bevel" -> SvgJoin.BEVEL; null -> p.join; else -> SvgJoin.MITER },
            opacity = opacity,
            evenOdd = attr("fill-rule")?.let { it == "evenodd" } ?: p.evenOdd,
            transform = own,
        )
    }

    private fun shape(xml: XmlPullParser, s: Style): SvgShape? {
        fun f(name: String, default: Float = 0f) = xml.getAttributeValue(null, name)?.let(::length) ?: default
        val kind = when (xml.name) {
            "path" -> SvgKind.PATH; "rect" -> SvgKind.RECT; "circle" -> SvgKind.CIRCLE; "ellipse" -> SvgKind.ELLIPSE
            "line" -> SvgKind.LINE; "polygon" -> SvgKind.POLYGON; "polyline" -> SvgKind.POLYLINE
            else -> return null
        }
        val points = xml.getAttributeValue(null, "points") ?: ""
        val n = when (kind) {
            SvgKind.PATH -> FloatArray(0)
            SvgKind.RECT -> floatArrayOf(f("x"), f("y"), f("width"), f("height"), f("rx", f("ry")), f("ry", f("rx")))
            SvgKind.CIRCLE -> floatArrayOf(f("cx"), f("cy"), f("r"))
            SvgKind.ELLIPSE -> floatArrayOf(f("cx"), f("cy"), f("rx"), f("ry"))
            SvgKind.LINE -> floatArrayOf(f("x1"), f("y1"), f("x2"), f("y2"))
            SvgKind.POLYGON, SvgKind.POLYLINE -> numbers(points).toFloatArray()
        }
        val data = if (kind == SvgKind.PATH) xml.getAttributeValue(null, "d") ?: return null else ""
        return SvgShape(
            kind, data, n, s.transform, withAlpha(s.fill, s.opacity), withAlpha(s.stroke, s.opacity),
            s.strokeWidth, s.cap, s.join, s.evenOdd,
        )
    }

    private val DRAWABLE = setOf("svg", "g", "path", "rect", "circle", "ellipse", "line", "polygon", "polyline")
    private val NUMBER = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    const val NONE = 0
    private const val OPAQUE_BLACK = 0xFF000000.toInt()
    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    private fun numbers(s: String): List<Float> = NUMBER.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()

    /** A length in user units; a trailing `px` is dropped, other units are not supported. */
    private fun length(s: String): Float? = s.trim().removeSuffix("px").toFloatOrNull()

    private fun withAlpha(argb: Int, factor: Float): Int {
        if (argb == NONE || factor >= 1f) return argb
        val a = ((argb ushr 24) * factor.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    /** `#rgb`, `#rrggbb`, `#rrggbbaa`, `white`, `black`; everything else (including `none`) is no paint. */
    fun color(text: String): Int {
        val s = text.trim().lowercase()
        if (s == "white") return 0xFFFFFFFF.toInt()
        if (s == "black") return OPAQUE_BLACK
        if (!s.startsWith("#")) return NONE
        val hex = s.drop(1)
        val v = hex.toLongOrNull(16) ?: return NONE
        return when (hex.length) {
            3 -> 0xFF000000.toInt() or (expand(v shr 8) shl 16) or (expand(v shr 4) shl 8) or expand(v)
            6 -> 0xFF000000.toInt() or v.toInt()
            8 -> ((v and 0xFF).toInt() shl 24) or (v shr 8).toInt()
            else -> NONE
        }
    }

    private fun expand(nibble: Long): Int = ((nibble and 0xF) * 17).toInt()

    private val TRANSFORM = Regex("(translate|scale|rotate|matrix)\\s*\\(([^)]*)\\)")

    /** Left to right, as SVG lists them: `translate(4 6) scale(.75)` scales first, then moves. */
    fun transform(text: String): FloatArray? {
        var m = IDENTITY
        for (t in TRANSFORM.findAll(text)) {
            val v = numbers(t.groupValues[2])
            val next = when (t.groupValues[1]) {
                "translate" -> if (v.isNotEmpty()) floatArrayOf(1f, 0f, 0f, 1f, v[0], v.getOrElse(1) { 0f }) else null
                "scale" -> if (v.isNotEmpty()) floatArrayOf(v[0], 0f, 0f, v.getOrElse(1) { v[0] }, 0f, 0f) else null
                "rotate" -> if (v.isNotEmpty()) rotation(v) else null
                else -> if (v.size == 6) v.toFloatArray() else null
            } ?: return null
            m = multiply(m, next)
        }
        return m
    }

    private fun rotation(v: List<Float>): FloatArray {
        val r = Math.toRadians(v[0].toDouble())
        val c = Math.cos(r).toFloat()
        val s = Math.sin(r).toFloat()
        val cx = v.getOrElse(1) { 0f }
        val cy = v.getOrElse(2) { 0f }
        return floatArrayOf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)
    }

    /** [a] applied after [b] (a * b in matrix order). */
    fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1],
        a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5],
    )
}
