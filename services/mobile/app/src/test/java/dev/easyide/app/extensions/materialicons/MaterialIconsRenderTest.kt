package dev.easyide.app.extensions.materialicons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.extensions.adapters.SvgParser
import dev.easyide.app.ui.theme.SvgRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer against every vendored SVG: no element or attribute the parser ignores may
 * matter to the picture, and each icon must draw something at the 16dp / 2.5x row size.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaterialIconsRenderTest {

    /** Elements the parser draws or resolves through references; anything else in the set would be silently dropped. */
    private val supportedTags = setOf(
        "svg", "g", "path", "rect", "circle", "ellipse", "line", "polygon", "polyline", "use", "defs",
        "linearGradient", "radialGradient", "stop", "clipPath", "title",
    )
    private val tag = Regex("<([A-Za-z][A-Za-z0-9]*)")
    private val paintValue = Regex("\\b(?:fill|stroke|stop-color)=\"([^\"]*)\"")
    private val unsupportedAttr = Regex("\\b(filter|mask|marker-start|marker-end|marker-mid)=")

    /** Icon id -> what the parser would ignore or misread; must be empty. */
    private fun unsupported(svg: String): List<String> {
        val out = ArrayList<String>()
        tag.findAll(svg).map { it.groupValues[1] }.filter { it !in supportedTags }.toSet().forEach { out += "element <$it>" }
        unsupportedAttr.findAll(svg).forEach { out += "attribute ${it.groupValues[1]}" }
        paintValue.findAll(svg).map { it.groupValues[1] }.forEach { v ->
            val ok = v == "none" || v.startsWith("url(#") || SvgParser.color(v) != 0
            if (!ok) out += "paint '$v'"
        }
        return out
    }

    @Test fun `no vendored svg uses a construct the renderer ignores`() {
        val report = MaterialIconsPack.definitions.mapNotNull { (id, file) ->
            unsupported(file.readText()).takeIf { it.isNotEmpty() }?.let { "$id: ${it.distinct()}" }
        }
        assertEquals(emptyList<String>(), report)
    }

    @Test fun `every vendored svg parses and draws at 40px without an empty result`() {
        val bad = ArrayList<String>()
        for ((id, file) in MaterialIconsPack.definitions) {
            val icon = SvgParser.parse(file.readText())
            if (icon == null || icon.shapes.isEmpty()) { bad += "$id: parse"; continue }
            if (opaquePixels(SvgRaster.render(icon, 40)) < 20) bad += "$id: nearly empty"
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun `gradient fills resolve to gradients and clips to shapes`() {
        val withGradient = MaterialIconsPack.definitions.filter { (_, f) -> f.readText().contains("fill=\"url(#") }
        assertTrue(withGradient.isNotEmpty())
        for ((id, file) in withGradient) {
            val icon = SvgParser.parse(file.readText())!!
            assertTrue("$id has no gradient shape", icon.shapes.any { it.fillGradient != null })
        }
        val clipped = MaterialIconsPack.definitions.filter { (_, f) -> f.readText().contains("clip-path=") }
        for ((id, file) in clipped) {
            val icon = SvgParser.parse(file.readText())!!
            assertTrue("$id clip lost", icon.shapes.any { s -> s.clips.any { it.shapes.isNotEmpty() } })
        }
    }

    @Test fun `use elements expand into the shapes they reference`() {
        val file = MaterialIconsPack.definitions.getValue("folder-css").readText()
        assertTrue(file.contains("<use"))
        val icon = SvgParser.parse(file)!!
        val uses = file.split("<use").size - 1
        // Each use draws the referenced shape again; the definition itself is not painted.
        assertEquals(file.split("<path").size - 1 - 1 + uses, icon.shapes.size)
    }

    @Test fun `python draws its two logo colours`() {
        val icon = SvgParser.parse(MaterialIconsPack.definitions.getValue("python").readText())!!
        val px = IntArray(40 * 40).also { SvgRaster.render(icon, 40).getPixels(it, 0, 40, 0, 0, 40, 40) }
        val opaque = px.filter { Color.alpha(it) > 200 }
        assertTrue(opaque.any { Color.blue(it) > Color.red(it) + 40 })
        assertTrue(opaque.any { Color.red(it) > Color.blue(it) + 40 })
    }

    private fun opaquePixels(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { Color.alpha(it) > 200 }
    }
}
