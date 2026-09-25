package dev.easyide.app.data.settings

import dev.easyide.app.R
import kotlin.math.roundToInt

/** The families `editor.fontFamily` accepts; the platform `monospace` is Droid Sans Mono on Android, VS Code's default on Linux. */
enum class EditorFont(val id: String) {
    MONOSPACE("monospace"), SANS("sans-serif"), GEIST_MONO("Geist Mono");

    companion object {
        /** A CSS-style family list ("'Droid Sans Mono', monospace") resolves to its first family this app knows. */
        fun firstKnown(list: String): EditorFont? = list.split(',')
            .map { it.trim().trim('\'', '"') }
            .firstNotNullOfOrNull { name -> entries.firstOrNull { it.id.equals(name, ignoreCase = true) } }
    }
}

enum class CursorStyle(val id: String) { LINE("line"), BLOCK("block"), UNDERLINE("underline") }

enum class LineNumbers(val id: String) { ON("on"), OFF("off"), RELATIVE("relative") }

/** `line` tints the caret line; `all` adds a border and tints the gutter number too; `gutter` only the gutter. */
enum class LineHighlight(val id: String) {
    NONE("none"), GUTTER("gutter"), LINE("line"), ALL("all");

    val paintsLine: Boolean get() = this == LINE || this == ALL
    val paintsBorder: Boolean get() = this == ALL
}

/** `always` also marks the pair enclosing the caret; `near` only a bracket the caret touches. */
enum class BracketMatching(val id: String) { ALWAYS("always"), NEAR("near"), NEVER("never") }

enum class ScrollbarVisibility(val id: String) { AUTO("auto"), VISIBLE("visible"), HIDDEN("hidden") }

enum class WordWrap(val id: String) { OFF("off"), ON("on") }

/**
 * The `editor.*` settings that shape the text area (typography, caret, decorations, wrapping),
 * mirroring VS Code's keys and defaults. Kept out of [SettingsSchema] so the editor look is one
 * file; `editor.fontSize` stays there because the pinch-zoom gesture and old settings files know it.
 */
object EditorSettingsSchema {

    private val C = SettingCategory.EDITOR

    /** Line height of `editor.lineHeight = 0`, as a multiple of the font size (VS Code's Linux default). */
    const val AUTO_LINE_HEIGHT_RATIO = 1.35

    /** Values up to this are a multiplier of the font size, larger ones are pixels (VS Code's rule). */
    const val LINE_HEIGHT_MULTIPLIER_MAX = 8.0

    val fontFamily = Setting.Enum(
        "editor.fontFamily", C, R.string.setting_editor_font_family_title, R.string.setting_editor_font_family_desc,
        EditorFont.MONOSPACE, SettingScope.L, EditorFont.entries,
        { when (it) { EditorFont.MONOSPACE -> R.string.editor_font_monospace; EditorFont.SANS -> R.string.editor_font_sans; EditorFont.GEIST_MONO -> R.string.editor_font_geist_mono } },
        aliases = { e -> SchemaValidator.stringOrNull(e)?.let(EditorFont::firstKnown) },
        id = EditorFont::id,
    )

    val lineHeight = Setting.Decimal(
        "editor.lineHeight", C, R.string.setting_editor_line_height_title, R.string.setting_editor_line_height_desc,
        default = 0.0, scope = SettingScope.L, min = 0.0, max = 150.0,
    )

    val letterSpacing = Setting.Decimal(
        "editor.letterSpacing", C, R.string.setting_editor_letter_spacing_title, R.string.setting_editor_letter_spacing_desc,
        default = 0.0, scope = SettingScope.L, min = -2.0, max = 8.0,
    )

    val cursorStyle = Setting.Enum(
        "editor.cursorStyle", C, R.string.setting_editor_cursor_style_title, R.string.setting_editor_cursor_style_desc,
        CursorStyle.LINE, SettingScope.L, CursorStyle.entries,
        { when (it) { CursorStyle.LINE -> R.string.cursor_style_line; CursorStyle.BLOCK -> R.string.cursor_style_block; CursorStyle.UNDERLINE -> R.string.cursor_style_underline } },
        id = CursorStyle::id,
    )

    val cursorWidth = Setting.IntRange(
        "editor.cursorWidth", C, R.string.setting_editor_cursor_width_title, R.string.setting_editor_cursor_width_desc,
        default = 2, scope = SettingScope.L, min = 1, max = 6,
    )

    val lineNumbers = Setting.Enum(
        "editor.lineNumbers", C, R.string.setting_editor_line_numbers_title, R.string.setting_editor_line_numbers_desc,
        LineNumbers.ON, SettingScope.L, LineNumbers.entries,
        { when (it) { LineNumbers.ON -> R.string.level_on; LineNumbers.OFF -> R.string.level_off; LineNumbers.RELATIVE -> R.string.line_numbers_relative } },
        id = LineNumbers::id,
    )

    val renderLineHighlight = Setting.Enum(
        "editor.renderLineHighlight", C, R.string.setting_editor_line_highlight_title, R.string.setting_editor_line_highlight_desc,
        LineHighlight.LINE, SettingScope.L, LineHighlight.entries,
        { when (it) { LineHighlight.NONE -> R.string.level_off; LineHighlight.GUTTER -> R.string.line_highlight_gutter; LineHighlight.LINE -> R.string.line_highlight_line; LineHighlight.ALL -> R.string.line_highlight_all } },
        id = LineHighlight::id,
    )

    val matchBrackets = Setting.Enum(
        "editor.matchBrackets", C, R.string.setting_editor_match_brackets_title, R.string.setting_editor_match_brackets_desc,
        BracketMatching.ALWAYS, SettingScope.L, BracketMatching.entries,
        { when (it) { BracketMatching.ALWAYS -> R.string.match_brackets_always; BracketMatching.NEAR -> R.string.match_brackets_near; BracketMatching.NEVER -> R.string.level_off } },
        id = BracketMatching::id,
    )

    val indentGuides = Setting.Bool(
        "editor.guides.indentation", C, R.string.setting_editor_indent_guides_title, R.string.setting_editor_indent_guides_desc,
        default = true, scope = SettingScope.L,
    )

    val scrollbarVertical = Setting.Enum(
        "editor.scrollbar.vertical", C, R.string.setting_editor_scrollbar_title, R.string.setting_editor_scrollbar_desc,
        ScrollbarVisibility.AUTO, SettingScope.L, ScrollbarVisibility.entries,
        { when (it) { ScrollbarVisibility.AUTO -> R.string.scrollbar_auto; ScrollbarVisibility.VISIBLE -> R.string.scrollbar_visible; ScrollbarVisibility.HIDDEN -> R.string.scrollbar_hidden } },
        id = ScrollbarVisibility::id,
    )

    val wordWrap = Setting.Enum(
        "editor.wordWrap", C, R.string.setting_editor_word_wrap_title, R.string.setting_editor_word_wrap_desc,
        WordWrap.OFF, SettingScope.L, WordWrap.entries,
        { when (it) { WordWrap.OFF -> R.string.level_off; WordWrap.ON -> R.string.level_on } },
        id = WordWrap::id,
    )

    val all: List<Setting<*>> = listOf(
        fontFamily, lineHeight, letterSpacing, cursorStyle, cursorWidth, lineNumbers, renderLineHighlight,
        matchBrackets, indentGuides, scrollbarVertical, wordWrap,
    )

    /** The resolved options for a document in [languageId]. Invalid stored values were skipped by resolution, so this never fails. */
    fun options(settings: SettingsSnapshot, languageId: String?): EditorOptions = EditorOptions(
        fontFamily = settings.get(fontFamily, languageId),
        lineHeight = settings.get(lineHeight, languageId),
        letterSpacing = settings.get(letterSpacing, languageId),
        cursorStyle = settings.get(cursorStyle, languageId),
        cursorWidth = settings.get(cursorWidth, languageId),
        lineNumbers = settings.get(lineNumbers, languageId),
        lineHighlight = settings.get(renderLineHighlight, languageId),
        matchBrackets = settings.get(matchBrackets, languageId),
        indentGuides = settings.get(indentGuides, languageId),
        scrollbar = settings.get(scrollbarVertical, languageId),
        wordWrap = settings.get(wordWrap, languageId),
    )
}

/** What the text area reads; immutable so the editor can key `remember` on it. */
data class EditorOptions(
    val fontFamily: EditorFont = EditorFont.MONOSPACE,
    val lineHeight: Double = 0.0,
    val letterSpacing: Double = 0.0,
    val cursorStyle: CursorStyle = CursorStyle.LINE,
    val cursorWidth: Int = 2,
    val lineNumbers: LineNumbers = LineNumbers.ON,
    val lineHighlight: LineHighlight = LineHighlight.LINE,
    val matchBrackets: BracketMatching = BracketMatching.ALWAYS,
    val indentGuides: Boolean = true,
    val scrollbar: ScrollbarVisibility = ScrollbarVisibility.AUTO,
    val wordWrap: WordWrap = WordWrap.OFF,
) {
    /**
     * The line pitch for [fontSize], in the same unit (sp). `0` is 1.35 x the size, a value up to 8 is
     * a multiplier, anything larger is that many units; never below the size, so lines cannot overlap.
     */
    fun lineHeightFor(fontSize: Int): Float {
        val raw = when {
            lineHeight == 0.0 -> fontSize * EditorSettingsSchema.AUTO_LINE_HEIGHT_RATIO
            lineHeight <= EditorSettingsSchema.LINE_HEIGHT_MULTIPLIER_MAX -> fontSize * lineHeight
            else -> lineHeight
        }
        return raw.roundToInt().coerceAtLeast(fontSize).toFloat()
    }
}
