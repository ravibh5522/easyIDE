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
 * A linear (`coords` = x1 y1 x2 y2) or radial (`coords` = cx cy r) gradient with its stops
 * resolved through `xlink:href` chains. [boxUnits]: coordinates are fractions of the painted
 * shape's bounding box (SVG's default), otherwise user units. [transform] is `gradientTransform`.
 */
class SvgGradient(
    val radial: Boolean,
    val coords: FloatArray,
    val offsets: FloatArray,
    val colors: IntArray,
    val boxUnits: Boolean,
    val transform: FloatArray,
)

/** A `clipPath`: the union of [shapes], whose transforms are already in icon space. */
class SvgClip(val shapes: List<SvgShape>)

/**
 * One drawable element with its style already resolved through the `<g>` and `<use>` ancestors:
 * [transform] is the accumulated 2x3 matrix (a b c d e f), colours are ARGB ints (0 = none,
 * opacity folded into the alpha), [n] the element's numeric attributes in SVG order. A gradient
 * fill is [fillGradient] with [fill] = 0 and its alpha in [gradientAlpha]; [clips] are the
 * `clip-path`s of the element and its ancestors, all of which must contain a pixel to paint it.
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
    val fillGradient: SvgGradient? = null,
    val gradientAlpha: Float = 1f,
    val clips: List<SvgClip> = emptyList(),
)

/** A parsed icon: the view box (origin and size) and its shapes in paint order. */
class SvgIcon(val width: Float, val height: Float, val shapes: List<SvgShape>, val minX: Float = 0f, val minY: Float = 0f)

/**
 * The SVG subset icon themes use: `svg` (viewBox), `g`, `use` (same-document `#id` only, with
 * x/y), `path`, `rect`, `circle`, `ellipse`, `line`, `polygon` and `polyline`; presentation
 * attributes (fill, stroke, stroke-width, linecap/linejoin, fill-rule, opacity, fill-opacity,
 * stroke-opacity, transform, clip-path) also from a `style` attribute, inherited down the tree;
 * `linearGradient` and `radialGradient` fills (stops, `xlink:href` inheritance, gradientUnits,
 * gradientTransform); and `clipPath`s of shapes. Transforms understand `translate`, `scale`,
 * `rotate` and `matrix`; colours are `#rgb`, `#rrggbb`, `#rrggbbaa`, `none`, `white` and `black`.
 * Filters, masks, text, images, `<style>` sheets and external references are not drawn, which keeps
 * an icon theme from reaching anything outside its own shapes.
 */
object SvgParser {

    /** Icon files are tiny; more elements than this is not an icon. */
    const val MAX_SHAPES = 512

    /** `use` chains deeper than this are a cycle or a bomb, not an icon. */
    private const val MAX_USE_DEPTH = 8
    private const val MAX_GRADIENT_CHAIN = 8

    private class El(val name: String, val attrs: Map<String, String>) {
        val kids = ArrayList<El>()
    }

    private class Style(
        val fill: Int = OPAQUE_BLACK, val fillGradient: SvgGradient? = null, val stroke: Int = NONE,
        val strokeWidth: Float = 1f, val cap: SvgCap = SvgCap.BUTT, val join: SvgJoin = SvgJoin.MITER,
        val opacity: Float = 1f, val fillOpacity: Float = 1f, val strokeOpacity: Float = 1f,
        val evenOdd: Boolean = false, val transform: FloatArray = IDENTITY, val clips: List<SvgClip> = emptyList(),
    )

    /** I/O boundary: [text] is a file's content, so malformed XML yields null instead of a throw. */
    fun parse(text: String): SvgIcon? = try {
        build(readTree(text))
    } catch (e: XmlPullParserException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun readTree(text: String): El? {
        val xml = Xml.newPullParser().apply { setInput(StringReader(text)) }
        val stack = ArrayList<El>()
        var root: El? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                XmlPullParser.START_TAG -> {
                    val el = El(xml.name, attributes(xml))
                    stack.lastOrNull()?.kids?.add(el)
                    if (root == null) root = el
                    stack += el
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
        }
        return root?.takeIf { it.name == "svg" }
    }

    /** Presentation attributes, with declarations of a `style` attribute laid over them as CSS does. */
    private fun attributes(xml: XmlPullParser): Map<String, String> {
        val out = HashMap<String, String>()
        for (i in 0 until xml.attributeCount) out[xml.getAttributeName(i)] = xml.getAttributeValue(i)
        out.remove("style")?.split(';')?.forEach { decl ->
            val at = decl.indexOf(':')
            if (at > 0) out[decl.substring(0, at).trim()] = decl.substring(at + 1).trim()
        }
        return out
    }

    private fun build(root: El?): SvgIcon? {
        root ?: return null
        val view = viewBox(root) ?: return null
        val doc = Doc(root)
        doc.visit(root, Style(), 0)
        if (doc.shapes.size > MAX_SHAPES) return null
        return SvgIcon(view[2], view[3], doc.shapes, view[0], view[1])
    }

    private fun viewBox(svg: El): FloatArray? {
        val v = numbers(svg.attrs["viewBox"] ?: "")
        if (v.size == 4 && v[2] > 0f && v[3] > 0f) return v.toFloatArray()
        val w = svg.attrs["width"]?.let(::length)
        val h = svg.attrs["height"]?.let(::length)
        return if (w != null && h != null && w > 0f && h > 0f) floatArrayOf(0f, 0f, w, h) else null
    }

    /** One document's element index and the shapes collected from it. */
    private class Doc(root: El) {
        val shapes = ArrayList<SvgShape>()
        private val ids = HashMap<String, El>()

        init {
            fun index(e: El) {
                e.attrs["id"]?.let { if (it !in ids) ids[it] = e }
                e.kids.forEach(::index)
            }
            index(root)
        }

        fun visit(el: El, parent: Style, depth: Int) {
            if (shapes.size > MAX_SHAPES || el.attrs["display"] == "none") return
            when (el.name) {
                "svg", "g", "a", "symbol" -> {
                    val s = styleOf(el, parent)
                    for (k in el.kids) visit(k, s, depth)
                }
                "use" -> use(el, parent, depth)
                in DRAWABLE -> shape(el, styleOf(el, parent))?.let(shapes::add)
            }
        }

        private fun use(el: El, parent: Style, depth: Int) {
            val ref = reference(el)?.let(ids::get) ?: return
            if (depth >= MAX_USE_DEPTH) return
            val s = styleOf(el, parent)
            val dx = el.attrs["x"]?.let(::length) ?: 0f
            val dy = el.attrs["y"]?.let(::length) ?: 0f
            val moved = multiply(s.transform, floatArrayOf(1f, 0f, 0f, 1f, dx, dy))
            visit(ref, s.withTransform(moved), depth + 1)
        }

        private fun reference(el: El): String? =
            (el.attrs["xlink:href"] ?: el.attrs["href"])?.takeIf { it.startsWith("#") }?.drop(1)

        private fun styleOf(el: El, p: Style): Style {
            val a = el.attrs
            val own = a["transform"]?.let(::transform)?.let { multiply(p.transform, it) } ?: p.transform
            var fill = p.fill
            var gradient = p.fillGradient
            a["fill"]?.let { text ->
                val ref = urlId(text)
                gradient = ref?.let(::gradient)
                fill = if (ref == null) color(text) else NONE
            }
            val clip = a["clip-path"]?.let(::urlId)?.let { clipOf(it, own) }
            return Style(
                fill = fill, fillGradient = gradient,
                stroke = a["stroke"]?.let(::color) ?: p.stroke,
                strokeWidth = a["stroke-width"]?.let(::length) ?: p.strokeWidth,
                cap = when (a["stroke-linecap"]) { "round" -> SvgCap.ROUND; "square" -> SvgCap.SQUARE; null -> p.cap; else -> SvgCap.BUTT },
                join = when (a["stroke-linejoin"]) { "round" -> SvgJoin.ROUND; "bevel" -> SvgJoin.BEVEL; null -> p.join; else -> SvgJoin.MITER },
                // Opacity multiplies down the tree; fill-opacity and stroke-opacity are inherited values.
                opacity = p.opacity * (a["opacity"]?.toFloatOrNull() ?: 1f),
                fillOpacity = a["fill-opacity"]?.toFloatOrNull() ?: p.fillOpacity,
                strokeOpacity = a["stroke-opacity"]?.toFloatOrNull() ?: p.strokeOpacity,
                evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: p.evenOdd,
                transform = own,
                clips = if (clip != null) p.clips + clip else p.clips,
            )
        }

        private fun Style.withTransform(m: FloatArray) = Style(
            fill, fillGradient, stroke, strokeWidth, cap, join, opacity, fillOpacity, strokeOpacity, evenOdd, m, clips,
        )

        /** The clip's shapes in icon space: the referencing element's [transform] is their base matrix. */
        private fun clipOf(id: String, transform: FloatArray): SvgClip? {
            val el = ids[id]?.takeIf { it.name == "clipPath" } ?: return null
            val inner = Doc(el).also { it.ids.putAll(ids) }
            for (k in el.kids) inner.visit(k, Style(transform = transform), MAX_USE_DEPTH - 1)
            return SvgClip(inner.shapes)
        }

        private fun gradient(id: String): SvgGradient? {
            val chain = generateSequence(ids[id]) { e -> reference(e)?.let(ids::get) }.take(MAX_GRADIENT_CHAIN).toList()
            val head = chain.firstOrNull() ?: return null
            if (head.name != "linearGradient" && head.name != "radialGradient") return null
            fun attr(name: String) = chain.firstNotNullOfOrNull { it.attrs[name] }
            val stops = chain.firstOrNull { e -> e.kids.any { it.name == "stop" } }?.kids?.filter { it.name == "stop" } ?: return null
            fun coord(name: String, default: Float) = attr(name)?.let(::fraction) ?: default
            val radial = head.name == "radialGradient"
            val coords = if (radial) floatArrayOf(coord("cx", .5f), coord("cy", .5f), coord("r", .5f))
            else floatArrayOf(coord("x1", 0f), coord("y1", 0f), coord("x2", 1f), coord("y2", 0f))
            var last = 0f
            val offsets = FloatArray(stops.size) { i ->
                last = maxOf(last, stops[i].attrs["offset"]?.let(::fraction)?.coerceIn(0f, 1f) ?: 0f)
                last
            }
            val colors = IntArray(stops.size) { i ->
                val a = stops[i].attrs
                withAlpha(a["stop-color"]?.let(::color) ?: OPAQUE_BLACK, a["stop-opacity"]?.toFloatOrNull() ?: 1f)
            }
            // A single stop is a flat colour; the shader needs two to interpolate between.
            val flat = stops.size == 1
            return SvgGradient(
                radial, coords, if (flat) floatArrayOf(0f, 1f) else offsets, if (flat) intArrayOf(colors[0], colors[0]) else colors,
                attr("gradientUnits") != "userSpaceOnUse", attr("gradientTransform")?.let(::transform) ?: IDENTITY,
            )
        }

        private fun shape(el: El, s: Style): SvgShape? {
            fun f(name: String, default: Float = 0f) = el.attrs[name]?.let(::length) ?: default
            val kind = when (el.name) {
                "path" -> SvgKind.PATH; "rect" -> SvgKind.RECT; "circle" -> SvgKind.CIRCLE; "ellipse" -> SvgKind.ELLIPSE
                "line" -> SvgKind.LINE; "polygon" -> SvgKind.POLYGON; else -> SvgKind.POLYLINE
            }
            val n = when (kind) {
                SvgKind.PATH -> FloatArray(0)
                SvgKind.RECT -> floatArrayOf(f("x"), f("y"), f("width"), f("height"), f("rx", f("ry")), f("ry", f("rx")))
                SvgKind.CIRCLE -> floatArrayOf(f("cx"), f("cy"), f("r"))
                SvgKind.ELLIPSE -> floatArrayOf(f("cx"), f("cy"), f("rx"), f("ry"))
                SvgKind.LINE -> floatArrayOf(f("x1"), f("y1"), f("x2"), f("y2"))
                SvgKind.POLYGON, SvgKind.POLYLINE -> numbers(el.attrs["points"] ?: "").toFloatArray()
            }
            val data = if (kind == SvgKind.PATH) el.attrs["d"] ?: return null else ""
            return SvgShape(
                kind, data, n, s.transform, withAlpha(s.fill, s.fillOpacity * s.opacity),
                withAlpha(s.stroke, s.strokeOpacity * s.opacity), s.strokeWidth, s.cap, s.join, s.evenOdd,
                s.fillGradient, s.fillOpacity * s.opacity, s.clips,
            )
        }
    }

    private val DRAWABLE = setOf("path", "rect", "circle", "ellipse", "line", "polygon", "polyline")
    private val NUMBER = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val URL_REF = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)")
    const val NONE = 0
    private const val OPAQUE_BLACK = 0xFF000000.toInt()
    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    private fun urlId(text: String): String? = URL_REF.find(text)?.groupValues?.get(1)

    private fun numbers(s: String): List<Float> = NUMBER.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()

    /** A length in user units; a trailing `px` is dropped, other units are not supported. */
    private fun length(s: String): Float? = s.trim().removeSuffix("px").toFloatOrNull()

    /** A gradient coordinate or stop offset: `50%` is .5, a bare number is itself. */
    private fun fraction(s: String): Float? {
        val t = s.trim()
        return if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.div(100f) else length(t)
    }

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
