package dev.easyide.app.ui.screens.workspace.files

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.BuiltInPalettes
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.app.ui.theme.toTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIconsTest {

    private val palettes = BuiltInPalettes.map { it.toTokens().toEditorColors() }

    @Test
    fun `every kind has a glyph and a real tint in every built-in palette`() {
        for (kind in FileKind.entries) {
            FileIcons.glyph(kind)
            for (colors in palettes) {
                assertNotEquals("$kind", Color.Unspecified, FileIcons.tint(kind, colors))
            }
        }
    }

    @Test
    fun `source files are tinted and chrome-like files are muted`() {
        val colors = palettes.first()
        assertEquals(colors.textMuted, FileIcons.tint(FileKind.LOCK, colors))
        assertEquals(colors.textMuted, FileIcons.tint(FileKind.DEFAULT, colors))
        assertNotEquals(colors.textMuted, FileIcons.tint(FileKind.PYTHON, colors))
        assertNotEquals(colors.textMuted, FileIcons.tint(FileKind.HTML, colors))
    }

    @Test
    fun `the main language families read differently`() {
        val colors = palettes.first()
        val tints = listOf(FileKind.JAVASCRIPT, FileKind.HTML, FileKind.PYTHON, FileKind.JSON, FileKind.PDF)
            .map { FileIcons.tint(it, colors) }
        assertTrue(tints.toSet().size >= 4)
    }
}
