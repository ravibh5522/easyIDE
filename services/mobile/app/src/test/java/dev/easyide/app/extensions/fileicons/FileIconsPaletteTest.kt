package dev.easyide.app.extensions.fileicons

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.ColorToken
import dev.easyide.app.ui.theme.GraphiteAmoledPalette
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.HighContrastDarkPalette
import dev.easyide.app.ui.theme.HighContrastLightPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.toTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The icon palette against the surfaces the icons sit on: the page fill must reach 3:1 (WCAG's
 * threshold for graphical objects) on the editor, panel and raised surfaces of every built-in palette
 * of its mode, and the glyph ink 4.5:1 on the page fill. Dark files are checked on the dark palettes;
 * light files on the light ones; a dark file with no light twin must also pass on the light ones.
 */
class FileIconsPaletteTest {

    private val surfaces = listOf(ColorToken.EDITOR_BACKGROUND, ColorToken.PANEL, ColorToken.RAISED)

    private fun surfacesOf(vararg palettes: dev.easyide.app.ui.theme.Palette) =
        palettes.flatMap { p -> val t = p.toTokens(); surfaces.map { t[it] } }

    private val dark = surfacesOf(GraphiteDarkPalette, GraphiteAmoledPalette, HighContrastDarkPalette)
    private val light = surfacesOf(PaperLightPalette, HighContrastLightPalette)

    private fun rgb(hex: String) = Color(hex.substring(1, 7).toLong(16).toInt() or (0xFF shl 24))

    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The first fill in the file is the page or folder body; the ink is the last colour a glyph uses, if any. */
    private fun colours(svg: String): List<String> = Regex("(?:fill|stroke)=\"(#[0-9a-fA-F]{6})\"").findAll(svg).map { it.groupValues[1] }.toList()

    @Test fun `contrast maths matches the WCAG reference values`() {
        assertEquals(21.0, contrast(Color.White, Color.Black), 0.01)
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.01)
    }

    @Test fun `every icon fill and ink is legible on the surfaces it appears on`() {
        val failures = ArrayList<String>()
        val defs = FileIconsPack.definitions
        for ((id, file) in defs) {
            val all = colours(file.readText())
            val fill = rgb(all.first())
            val onLight = id.endsWith("-light") || "$id-light" !in defs
            val onDark = !id.endsWith("-light")
            if (onDark) dark.forEach { if (contrast(fill, it) < 3.0) failures += "$id fill ${all.first()} on dark ${"%.2f".format(contrast(fill, it))}" }
            if (onLight) light.forEach { if (contrast(fill, it) < 3.0) failures += "$id fill ${all.first()} on light ${"%.2f".format(contrast(fill, it))}" }
            // Every other opaque colour in the file is glyph ink (a folder repeats its fill for the front panel).
            all.drop(1).filter { it.lowercase() != all.first().lowercase() }.distinct().forEach { ink ->
                val r = contrast(rgb(ink), fill)
                if (r < 4.5) failures += "$id ink $ink on ${all.first()} ${"%.2f".format(r)}"
            }
        }
        assertTrue("contrast failures:\n" + failures.take(40).joinToString("\n"), failures.isEmpty())
    }
}
