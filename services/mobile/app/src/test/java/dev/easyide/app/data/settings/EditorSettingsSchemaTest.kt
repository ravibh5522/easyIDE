package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSettingsSchemaTest {

    @Test fun `defaults are VS Code's`() {
        val o = EditorOptions()
        assertEquals(14, SettingsSchema.editorFontSize.default)
        assertEquals(EditorFont.MONOSPACE, EditorSettingsSchema.fontFamily.default)
        assertEquals(0.0, EditorSettingsSchema.lineHeight.default, 0.0)
        assertEquals(0.0, EditorSettingsSchema.letterSpacing.default, 0.0)
        assertEquals(CursorStyle.LINE, EditorSettingsSchema.cursorStyle.default)
        assertEquals(2, EditorSettingsSchema.cursorWidth.default)
        assertEquals(LineNumbers.ON, EditorSettingsSchema.lineNumbers.default)
        assertEquals(LineHighlight.LINE, EditorSettingsSchema.renderLineHighlight.default)
        assertEquals(BracketMatching.ALWAYS, EditorSettingsSchema.matchBrackets.default)
        assertEquals(true, EditorSettingsSchema.indentGuides.default)
        assertEquals(ScrollbarVisibility.AUTO, EditorSettingsSchema.scrollbarVertical.default)
        assertEquals(WordWrap.OFF, EditorSettingsSchema.wordWrap.default)
        assertEquals(o, EditorSettingsSchema.options(SettingsSnapshot.DEFAULTS, null))
    }

    @Test fun `every setting is declared once in the schema under the editor page`() {
        val keys = SettingsSchema.all.map { it.key }
        assertEquals(keys.distinct(), keys)
        EditorSettingsSchema.all.forEach { assertTrue(it.key, it.key in keys) }
        assertEquals(
            setOf(
                "editor.fontFamily", "editor.lineHeight", "editor.letterSpacing", "editor.cursorStyle", "editor.cursorWidth",
                "editor.lineNumbers", "editor.renderLineHighlight", "editor.matchBrackets", "editor.guides.indentation",
                "editor.scrollbar.vertical", "editor.wordWrap",
            ),
            EditorSettingsSchema.all.map { it.key }.toSet(),
        )
    }

    @Test fun `enum settings store VS Code's spellings and reject others`() {
        assertEquals(CursorStyle.BLOCK, EditorSettingsSchema.cursorStyle.decode(JsonPrimitive("block")))
        assertNull(EditorSettingsSchema.cursorStyle.decode(JsonPrimitive("block-outline")))
        assertEquals(LineNumbers.RELATIVE, EditorSettingsSchema.lineNumbers.decode(JsonPrimitive("relative")))
        assertNull(EditorSettingsSchema.lineNumbers.decode(JsonPrimitive("interval")))
        assertEquals(LineHighlight.ALL, EditorSettingsSchema.renderLineHighlight.decode(JsonPrimitive("all")))
        assertEquals(BracketMatching.NEAR, EditorSettingsSchema.matchBrackets.decode(JsonPrimitive("near")))
        assertEquals(ScrollbarVisibility.HIDDEN, EditorSettingsSchema.scrollbarVertical.decode(JsonPrimitive("hidden")))
        assertEquals(WordWrap.ON, EditorSettingsSchema.wordWrap.decode(JsonPrimitive("on")))
        assertEquals("Geist Mono", EditorSettingsSchema.fontFamily.encode(EditorFont.GEIST_MONO).let { (it as JsonPrimitive).content })
    }

    @Test fun `a CSS family list resolves to the first family the app has`() {
        val s = EditorSettingsSchema.fontFamily
        assertEquals(EditorFont.MONOSPACE, s.decode(JsonPrimitive("'Droid Sans Mono', 'monospace'")))
        assertEquals(EditorFont.GEIST_MONO, s.decode(JsonPrimitive("\"Geist Mono\", monospace")))
        assertEquals(EditorFont.SANS, s.decode(JsonPrimitive("Inter, sans-serif")))
        assertNull(s.decode(JsonPrimitive("Comic Sans")))
    }

    @Test fun `numbers are range checked`() {
        assertEquals(1.35, EditorSettingsSchema.lineHeight.decode(JsonPrimitive(1.35))!!, 0.0)
        assertEquals(20.0, EditorSettingsSchema.lineHeight.decode(JsonPrimitive(20))!!, 0.0)
        assertNull(EditorSettingsSchema.lineHeight.decode(JsonPrimitive(-1)))
        assertNull(EditorSettingsSchema.lineHeight.decode(JsonPrimitive(500)))
        assertNull(EditorSettingsSchema.lineHeight.decode(JsonPrimitive("1.5")))
        assertNull(EditorSettingsSchema.letterSpacing.decode(JsonPrimitive(99)))
        assertEquals(6, EditorSettingsSchema.cursorWidth.decode(JsonPrimitive(6)))
        assertNull(EditorSettingsSchema.cursorWidth.decode(JsonPrimitive(0)))
        assertNull(EditorSettingsSchema.cursorWidth.decode(JsonPrimitive(7)))
    }

    @Test fun `line height 0 is 1_35 times the size rounded, a small number multiplies, a large one is absolute`() {
        assertEquals(19f, EditorOptions().lineHeightFor(14))
        assertEquals(18f, EditorOptions().lineHeightFor(13))
        assertEquals(21f, EditorOptions(lineHeight = 1.5).lineHeightFor(14))
        assertEquals(8f * 14, EditorOptions(lineHeight = 8.0).lineHeightFor(14))
        assertEquals(24f, EditorOptions(lineHeight = 24.0).lineHeightFor(14))
    }

    @Test fun `the line height never drops below the font size`() {
        assertEquals(14f, EditorOptions(lineHeight = 0.5).lineHeightFor(14))
        assertEquals(20f, EditorOptions(lineHeight = 9.0).lineHeightFor(20))
    }

    @Test fun `line height follows a scaled font size`() {
        // 1.3 x and 2 x font scales reach the style as a bigger size, and the height is derived from it.
        val o = EditorOptions()
        listOf(14, 18, 28).forEach { assertTrue(o.lineHeightFor(it) >= it * 1.3f) }
    }

    @Test fun `line highlight modes say what they paint`() {
        assertEquals(listOf(false, false, true, true), LineHighlight.entries.map { it.paintsLine })
        assertEquals(listOf(false, false, false, true), LineHighlight.entries.map { it.paintsBorder })
    }
}
