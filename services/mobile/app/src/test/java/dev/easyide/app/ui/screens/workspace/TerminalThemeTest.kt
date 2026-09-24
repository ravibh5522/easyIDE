package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TextStyle
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.app.ui.theme.toTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalThemeTest {

    @Test fun `palette fills the ANSI slots and the default colours only`() {
        val palette = PaperLightPalette.toTokens().toEditorColors().terminal
        val untouched = 0x12345678
        val table = IntArray(TextStyle.NUM_INDEXED_COLORS) { untouched }

        TerminalTheme.writeDefaults(palette, table)

        palette.ansi.forEachIndexed { index, color -> assertEquals("ansi $index", color.toArgb(), table[index]) }
        assertEquals(palette.foreground.toArgb(), table[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(palette.background.toArgb(), table[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(palette.cursor.toArgb(), table[TextStyle.COLOR_INDEX_CURSOR])
        // The 256-colour cube belongs to the emulator.
        (palette.ansi.size until TextStyle.COLOR_INDEX_FOREGROUND).forEach { assertEquals(untouched, table[it]) }
    }

    @Test fun `light and dark terminals differ in background`() {
        val light = PaperLightPalette.toTokens().toEditorColors().terminal
        val dark = GraphiteDarkPalette.toTokens().toEditorColors().terminal
        assertEquals(PaperLightPalette.neutrals.editor, light.background)
        assertEquals(GraphiteDarkPalette.neutrals.editor, dark.background)
    }
}
