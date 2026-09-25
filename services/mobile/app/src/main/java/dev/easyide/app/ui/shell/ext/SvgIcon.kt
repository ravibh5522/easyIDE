package dev.easyide.app.ui.shell.ext

/** One drawable shape of a pack icon: SVG path data, and how it is painted. Colours are dropped: the shell tints the whole icon. */
data class SvgShape(val data: String, val fill: Boolean, val stroke: Boolean, val strokeWidth: Float, val round: Boolean)

/**
 * A pack's SVG icon reduced to what a tinted glyph needs: its shapes as path data. Reads `path`, `circle`, `ellipse`,
 * `rect`, `line`, `polyline` and `polygon`, with `fill`, `stroke`, `stroke-width` and `stroke-linecap`/`linejoin` inherited
 * from the `svg` element the way SVG does. Anything else is ignored: the validator has already refused what cannot be
 * tinted (gradients, images, scripts), so this reads text it trusts to be simple, and a malformed number is skipped.
 */
data class SvgIcon(val shapes: List<SvgShape>) {
    companion object {
        private val ELEMENT = Regex("""<\s*(svg|path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*"([^"]*)"|([\w:-]+)\s*=\s*'([^']*)'""")

        fun parse(svg: String): SvgIcon? {
            var root = Style()
            val shapes = ArrayList<SvgShape>()
            for (m in ELEMENT.findAll(svg)) {
                val tag = m.groupValues[1].lowercase()
                val attrs = attributes(m.groupValues[2])
                if (tag == "svg") { root = Style.of(attrs, Style()); continue }
                val style = Style.of(attrs, root)
                val data = pathData(tag, attrs) ?: continue
                shapes += SvgShape(data, style.fill, style.stroke, style.width, style.round)
            }
            return SvgIcon(shapes).takeIf { shapes.isNotEmpty() }
        }

        private fun attributes(text: String): Map<String, String> =
            ATTRIBUTE.findAll(text).associate { (it.groupValues[1].ifEmpty { it.groupValues[3] }).lowercase() to it.groupValues[2].ifEmpty { it.groupValues[4] } }

        /** Paint inherited down the tree: unset fill is filled (SVG's default), unset stroke is none. */
        private data class Style(val fill: Boolean = true, val stroke: Boolean = false, val width: Float = 1f, val round: Boolean = false) {
            companion object {
                fun of(a: Map<String, String>, parent: Style) = Style(
                    fill = a["fill"]?.let { it != "none" } ?: parent.fill,
                    stroke = a["stroke"]?.let { it != "none" } ?: parent.stroke,
                    width = a["stroke-width"]?.toFloatOrNull() ?: parent.width,
                    round = a["stroke-linecap"]?.let { it == "round" } ?: parent.round,
                )
            }
        }

        private fun num(a: Map<String, String>, key: String, default: Float = 0f): Float = a[key]?.toFloatOrNull() ?: default

        private fun pathData(tag: String, a: Map<String, String>): String? = when (tag) {
            "path" -> a["d"]?.takeIf { it.isNotBlank() }
            "circle" -> ellipse(num(a, "cx"), num(a, "cy"), num(a, "r"), num(a, "r"))
            "ellipse" -> ellipse(num(a, "cx"), num(a, "cy"), num(a, "rx"), num(a, "ry"))
            "rect" -> rect(num(a, "x"), num(a, "y"), num(a, "width"), num(a, "height"))
            "line" -> "M${num(a, "x1")} ${num(a, "y1")}L${num(a, "x2")} ${num(a, "y2")}"
            "polyline" -> points(a["points"], close = false)
            "polygon" -> points(a["points"], close = true)
            else -> null
        }

        private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String? =
            if (rx <= 0f || ry <= 0f) null
            else "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0Z"

        private fun rect(x: Float, y: Float, w: Float, h: Float): String? =
            if (w <= 0f || h <= 0f) null else "M$x ${y}h${w}v${h}h${-w}Z"

        private fun points(text: String?, close: Boolean): String? {
            val n = text?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }?.mapNotNull { it.toFloatOrNull() }.orEmpty()
            if (n.size < 4) return null
            val pairs = n.chunked(2).filter { it.size == 2 }
            return pairs.joinToString("L", prefix = "M", postfix = if (close) "Z" else "") { "${it[0]} ${it[1]}" }
        }
    }
}
