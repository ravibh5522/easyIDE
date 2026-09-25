package dev.easyide.app.extensions.fileicons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.extensions.adapters.SvgKind
import dev.easyide.app.extensions.adapters.SvgParser
import dev.easyide.app.ui.theme.SvgRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The SVG subset the icon themes use: parsing, inherited style, transforms, and real pixels at 16dp on a 2.5x screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SvgIconTest {

    private fun parse(body: String, attrs: String = "viewBox=\"0 0 16 16\"") = SvgParser.parse("<svg xmlns=\"http://www.w3.org/2000/svg\" $attrs>$body</svg>")

    @Test fun `colours accept the short and long hex forms`() {
        assertEquals(0xFFFF0000.toInt(), SvgParser.color("#f00"))
        assertEquals(0xFF112233.toInt(), SvgParser.color("#112233"))
        assertEquals(0x80112233.toInt(), SvgParser.color("#11223380"))
        assertEquals(0, SvgParser.color("none"))
        assertEquals(0xFFFFFFFF.toInt(), SvgParser.color("white"))
    }

    @Test fun `groups pass style and transform down to their shapes`() {
        val icon = parse("<g fill=\"#00f\" stroke=\"#f00\" stroke-width=\"2\" stroke-linecap=\"round\" transform=\"translate(4 6)\"><g transform=\"scale(.5)\"><path d=\"M0 0h8v8z\"/></g></g><rect x=\"1\" y=\"2\" width=\"3\" height=\"4\" fill=\"#0f0\"/>")!!
        val (path, rect) = icon.shapes
        assertEquals(SvgKind.PATH, path.kind)
        assertEquals(0xFF0000FF.toInt(), path.fill)
        assertEquals(0xFFFF0000.toInt(), path.stroke)
        assertEquals(2f, path.strokeWidth)
        // scale(.5) applies before translate(4 6): (0.5, 0, 0, 0.5, 4, 6).
        assertEquals(listOf(0.5f, 0f, 0f, 0.5f, 4f, 6f), path.transform.toList())
        assertEquals(0xFF00FF00.toInt(), rect.fill)
        assertEquals(0, rect.stroke)
    }

    @Test fun `opacity folds into the alpha and unknown elements are skipped with their children`() {
        val icon = parse("<g opacity=\".5\"><path fill=\"#fff\" d=\"M0 0h1v1z\"/></g><defs><linearGradient><stop/></linearGradient></defs><text>hi</text><circle cx=\"8\" cy=\"8\" r=\"2\"/>")!!
        assertEquals(2, icon.shapes.size)
        assertEquals(0x7FFFFFFF, icon.shapes[0].fill and 0xFFFFFFFF.toInt() or 0)
        assertEquals(SvgKind.CIRCLE, icon.shapes[1].kind)
    }

    @Test fun `no view box and no size, or broken xml, is not an icon`() {
        assertNull(parse("<path d=\"M0 0z\"/>", attrs = ""))
        assertNull(SvgParser.parse("<svg viewBox='0 0 16 16'><path"))
        assertNull(SvgParser.parse("not xml"))
    }

    @Test fun `a bad path draws nothing instead of throwing`() {
        val icon = parse("<path d=\"M1 1 Q\"/><rect width=\"4\" height=\"4\" fill=\"#fff\"/>")!!
        val bitmap = SvgRaster.render(icon, 16)
        assertEquals(Color.WHITE, bitmap.getPixel(1, 1))
    }

    @Test fun `every shipped svg parses, renders, and is transparent at the corners`() {
        val bad = ArrayList<String>()
        for ((id, file) in FileIconsPack.definitions) {
            val icon = SvgParser.parse(file.readText())
            if (icon == null || icon.shapes.isEmpty()) { bad += "$id: parse"; continue }
            val bitmap = SvgRaster.render(icon, 40)
            if (opaquePixels(bitmap) < 100) bad += "$id: nearly empty"
            if (Color.alpha(bitmap.getPixel(0, 0)) != 0) bad += "$id: corner painted"
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun `the page fill is the icon colour at 16dp on a 2_5x screen`() {
        val svg = FileIconsPack.definitions.getValue("kotlin").readText()
        val icon = SvgParser.parse(svg)!!
        val bitmap = SvgRaster.render(icon, 40)
        assertEquals(40, bitmap.width)
        val fill = Regex("fill=\"#([0-9a-f]{6})\"").find(svg)!!.groupValues[1].toInt(16)
        // Left margin of the page body, clear of the glyph.
        assertEquals(0xFF000000.toInt() or fill, bitmap.getPixel(10, 30))
        assertNotNull(bitmap)
    }

    private fun opaquePixels(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { Color.alpha(it) > 200 }
    }
}
