package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The launcher icon and splash read XML colours; they must stay the graphite-dark and paper-light palette values. */
class IdentityColorsTest {
    // Unit tests run with the module directory as working directory.
    private fun res(dir: String, name: String): Color {
        val xml = File("src/main/res/$dir/colors_identity.xml").readText()
        val hex = Regex("""name="$name">#([0-9A-Fa-f]{8})<""").find(xml)!!.groupValues[1]
        return Color(hex.toLong(HEX_RADIX))
    }

    @Test fun `launcher icon uses the graphite dark palette`() {
        assertEquals(GraphiteDarkPalette.neutrals.editor, res("values", "icon_background"))
        assertEquals(GraphiteDarkPalette.neutrals.text, res("values", "icon_ink"))
        assertEquals(GraphiteDarkPalette.accent.accent, res("values", "icon_accent"))
    }

    @Test fun `splash follows the day and night palettes`() {
        assertEquals(PaperLightPalette.neutrals.text, res("values", "splash_ink"))
        assertEquals(PaperLightPalette.accent.accent, res("values", "splash_accent"))
        assertEquals(GraphiteDarkPalette.neutrals.text, res("values-night", "splash_ink"))
        assertEquals(GraphiteDarkPalette.accent.accent, res("values-night", "splash_accent"))
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}
