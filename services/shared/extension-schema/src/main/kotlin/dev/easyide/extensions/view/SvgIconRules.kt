package dev.easyide.extensions.view

/**
 * The rules a pack icon must meet to be tinted by the shell (extension-ui.md sections 2.1 and 9.3:
 * monochrome only, no light/dark variants): a square 24 grid, one paint colour, nothing that
 * brings in raster data, scripts, external files or effects. Checked on the text with patterns
 * instead of an XML parser: the file is untrusted, and no entity or DTD machinery may touch it.
 */
object SvgIconRules {
    private val VIEW_BOX = Regex("""viewBox\s*=\s*["']\s*(-?[\d.]+)[\s,]+(-?[\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*["']""")
    private val FORBIDDEN = Regex(
        """<\s*(script|image|foreignObject|linearGradient|radialGradient|pattern|filter|style|use|animate\w*|set)\b|""" +
            """\bon[a-z]+\s*=|href\s*=|url\(\s*["']?(?!#)|data:|@import""",
        RegexOption.IGNORE_CASE,
    )
    private val PAINT = Regex("""\b(?:fill|stroke|stop-color|flood-color)\s*[=:]\s*["']?\s*([^"';\s>]+)""", RegexOption.IGNORE_CASE)
    private val UNPAINTED = setOf("none", "transparent", "inherit")

    /** The problems of [svg] as author-facing sentences; empty when it is a valid tintable icon. */
    fun check(svg: String, byteSize: Int): List<String> {
        val out = ArrayList<String>()
        if (byteSize > ViewLimits.ICON_MAX_BYTES) out += "larger than ${ViewLimits.ICON_MAX_BYTES / 1024} KB"
        if (!svg.contains("<svg", ignoreCase = true)) return out + "not an SVG document"
        val box = VIEW_BOX.find(svg)
        if (box == null) out += "needs a viewBox of 0 0 ${ViewLimits.ICON_GRID} ${ViewLimits.ICON_GRID}"
        else {
            val (x, y, w, h) = box.destructured.toList().map { it.toDoubleOrNull() }
            val onGrid = x == 0.0 && y == 0.0 && w == ViewLimits.ICON_GRID.toDouble() && h == ViewLimits.ICON_GRID.toDouble()
            if (!onGrid) out += "viewBox must be 0 0 ${ViewLimits.ICON_GRID} ${ViewLimits.ICON_GRID} (the 24 grid)"
        }
        FORBIDDEN.find(svg)?.let { out += "uses '${it.value.trim()}', which a tinted icon cannot" }
        val colours = PAINT.findAll(svg).map { it.groupValues[1].lowercase() }.filter { it !in UNPAINTED }.toSet()
        if (colours.size > 1) out += "uses ${colours.size} paint colours (${colours.sorted().joinToString()}); icons are monochrome"
        return out
    }
}
