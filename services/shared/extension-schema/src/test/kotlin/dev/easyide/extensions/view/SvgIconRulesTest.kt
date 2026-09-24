package dev.easyide.extensions.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconRulesTest {
    private val good = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M4 4h16"/></svg>"""

    private fun problems(svg: String) = SvgIconRules.check(svg, svg.length)

    @Test fun `a single-colour icon on the 24 grid is valid`() {
        assertEquals(emptyList<String>(), problems(good))
        assertEquals(emptyList<String>(), problems(good.replace("currentColor", "#1a1a1a")))
    }

    @Test fun `the grid and viewBox are required`() {
        assertTrue(problems(good.replace("0 0 24 24", "0 0 32 32")).single().contains("24 grid"))
        assertTrue(problems(good.replace("0 0 24 24", "2 2 24 24")).single().contains("24 grid"))
        assertTrue(problems(good.replace(" viewBox=\"0 0 24 24\"", "")).single().contains("viewBox"))
        assertTrue(problems("<p>not an icon</p>").single().contains("not an SVG"))
    }

    @Test fun `more than one paint colour is refused`() {
        val two = good.replace("<path", "<path fill=\"#ff0000\"/><path stroke=\"blue\"")
        assertTrue(problems(two).any { it.contains("monochrome") })
        // none and transparent are not colours.
        assertEquals(emptyList<String>(), problems(good.replace("<path", "<path fill=\"none\" stroke=\"currentColor\"")))
    }

    @Test fun `raster data, scripts, gradients and effects are refused`() {
        listOf("<image href=\"a.png\"/>", "<script>alert(1)</script>", "<linearGradient id=\"g\"/>", "<filter id=\"f\"/>", "<style>a{}</style>",
            "<foreignObject/>", "<use href=\"#a\"/>", "<path onclick=\"x()\" d=\"M0 0\"/>", "<path fill=\"url(http://x/y)\" d=\"M0 0\"/>",
            "<path d=\"M0 0\" style=\"background:url(data:image/png;base64,AA)\"/>",
        ).forEach { bad ->
            assertTrue(bad, problems(good.replace("<path d=\"M4 4h16\"/>", bad)).isNotEmpty())
        }
    }

    @Test fun `a local url reference is not raster or external`() {
        assertEquals(emptyList<String>(), problems(good.replace("<path d=\"M4 4h16\"/>", "<path d=\"M0 0\" clip-path=\"url(#c)\"/>")))
    }

    @Test fun `size is limited`() {
        assertTrue(SvgIconRules.check(good, ViewLimits.ICON_MAX_BYTES + 1).single().contains("KB"))
    }
}
