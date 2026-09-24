package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The few hand-picked colours a built-in theme is made of. Everything else in
 * [ThemeTokens] is derived from these by [toTokens], so the ~80 tokens stay
 * mutually consistent: a new theme is a new [Palette], never a new token table.
 */
data class Palette(
    val isDark: Boolean,
    val neutrals: Neutrals,
    val accent: Accent,
    val signals: Signals,
    /** Eight categorical commit-graph lane colours, distinct in hue, even in lightness. */
    val lanes: List<Color>,
    /** ANSI colours 0-15 in SGR order. */
    val ansi: List<Color>,
    val syntax: SyntaxColors,
    val emphasis: Emphasis,
) {
    init {
        require(lanes.size == ColorToken.LANES.size) { "a palette needs ${ColorToken.LANES.size} lane colours" }
        require(ansi.size == ColorToken.ANSI.size) { "a palette needs ${ColorToken.ANSI.size} ANSI colours" }
    }
}

/** The graphite (dark) or paper (light) ramp: four tonal surfaces, a hairline and three text weights. */
data class Neutrals(
    val editor: Color,
    val panel: Color,
    val raised: Color,
    val overlay: Color,
    val hairline: Color,
    val text: Color,
    /** Secondary text: labels, inactive tabs, status bar. */
    val textMuted: Color,
    /** Tertiary text: line numbers, comments-adjacent chrome, disabled content. */
    val textFaint: Color,
)

/** The single accent and the colour drawn on top of it. */
data class Accent(val accent: Color, val onAccent: Color)

/** Status and source-control semantics. Untracked shares [gitAdded]: both mean "new here". */
data class Signals(
    val error: Color,
    val warning: Color,
    val info: Color,
    val success: Color,
    val gitAdded: Color,
    val gitModified: Color,
    val gitDeleted: Color,
    val gitConflict: Color,
)

/**
 * Translucency levels for tints that paint under text. High-contrast palettes
 * raise them so selection and the current line stay visible without colour.
 */
data class Emphasis(
    val selection: Float,
    val currentLine: Float,
    val listSelection: Float,
    val bracketMatch: Float,
    val wordHighlight: Float,
    val searchMatch: Float,
    val searchMatchCurrent: Float,
    val inlayBackground: Float,
)

/**
 * The derivation table: which palette colour each token takes. Kept as one
 * exhaustive `when` so adding a [ColorToken] fails to compile until it has a
 * source here.
 */
fun Palette.toTokens(): ThemeTokens {
    val n = neutrals
    val a = accent.accent
    val s = signals
    val e = emphasis
    return ThemeTokens.of(isDark, syntax) { token ->
        when (token) {
            ColorToken.EDITOR_BACKGROUND, ColorToken.GUTTER_BACKGROUND, ColorToken.TAB_ACTIVE,
            ColorToken.TERMINAL_BACKGROUND -> n.editor
            // Surface-coloured bars: tonal separation, not a blue status strip.
            ColorToken.PANEL, ColorToken.ACTIVITY_BAR, ColorToken.STATUS_BAR, ColorToken.TAB_INACTIVE -> n.panel
            ColorToken.RAISED -> n.raised
            ColorToken.OVERLAY -> n.overlay
            ColorToken.HAIRLINE, ColorToken.INDENT_GUIDE -> n.hairline

            ColorToken.FOREGROUND, ColorToken.EDITOR_FOREGROUND, ColorToken.LINE_NUMBER_ACTIVE,
            ColorToken.TAB_ACTIVE_FOREGROUND, ColorToken.ACTIVITY_BAR_FOREGROUND,
            ColorToken.LIST_ACTIVE_SELECTION_FOREGROUND, ColorToken.TERMINAL_FOREGROUND -> n.text
            ColorToken.TEXT_MUTED, ColorToken.STATUS_BAR_FOREGROUND, ColorToken.TAB_INACTIVE_FOREGROUND,
            ColorToken.ACTIVITY_BAR_INACTIVE_FOREGROUND, ColorToken.INLAY_HINT_FOREGROUND,
            ColorToken.CODE_LENS, ColorToken.DIAGNOSTIC_HINT -> n.textMuted
            ColorToken.TEXT_DISABLED, ColorToken.LINE_NUMBER -> n.textFaint

            ColorToken.ACCENT, ColorToken.FOCUS_BORDER, ColorToken.TAB_ACTIVE_BORDER,
            ColorToken.ACTIVITY_BAR_ACTIVE_BORDER, ColorToken.CURSOR, ColorToken.TERMINAL_CURSOR -> a
            ColorToken.ON_ACCENT -> accent.onAccent
            ColorToken.SELECTION -> a.copy(alpha = e.selection)
            ColorToken.CURRENT_LINE -> n.text.copy(alpha = e.currentLine)
            ColorToken.BRACKET_MATCH -> a.copy(alpha = e.bracketMatch)
            // Opaque, so a row's own background never shows through the pill.
            ColorToken.LIST_ACTIVE_SELECTION -> a.copy(alpha = e.listSelection).compositeOver(n.panel)

            ColorToken.ERROR, ColorToken.DIAGNOSTIC_ERROR, ColorToken.GIT_DELETED -> s.error
            ColorToken.WARNING, ColorToken.DIAGNOSTIC_WARNING, ColorToken.LIGHTBULB -> s.warning
            ColorToken.INFO, ColorToken.DIAGNOSTIC_INFORMATION -> s.info
            ColorToken.SUCCESS -> s.success
            ColorToken.GIT_ADDED, ColorToken.GIT_UNTRACKED -> s.gitAdded
            ColorToken.GIT_MODIFIED -> s.gitModified
            ColorToken.GIT_CONFLICT -> s.gitConflict

            ColorToken.LANE_1, ColorToken.LANE_2, ColorToken.LANE_3, ColorToken.LANE_4,
            ColorToken.LANE_5, ColorToken.LANE_6, ColorToken.LANE_7, ColorToken.LANE_8 ->
                lanes[ColorToken.LANES.indexOf(token)]

            ColorToken.HIGHLIGHT_TEXT, ColorToken.HIGHLIGHT_READ -> n.text.copy(alpha = e.wordHighlight)
            ColorToken.HIGHLIGHT_WRITE -> s.info.copy(alpha = e.wordHighlight * 2)
            ColorToken.SEARCH_MATCH -> s.warning.copy(alpha = e.searchMatch)
            ColorToken.SEARCH_MATCH_CURRENT -> s.warning.copy(alpha = e.searchMatchCurrent)
            ColorToken.INLAY_HINT_BACKGROUND -> n.text.copy(alpha = e.inlayBackground)

            ColorToken.ANSI_BLACK, ColorToken.ANSI_RED, ColorToken.ANSI_GREEN, ColorToken.ANSI_YELLOW,
            ColorToken.ANSI_BLUE, ColorToken.ANSI_MAGENTA, ColorToken.ANSI_CYAN, ColorToken.ANSI_WHITE,
            ColorToken.ANSI_BRIGHT_BLACK, ColorToken.ANSI_BRIGHT_RED, ColorToken.ANSI_BRIGHT_GREEN,
            ColorToken.ANSI_BRIGHT_YELLOW, ColorToken.ANSI_BRIGHT_BLUE, ColorToken.ANSI_BRIGHT_MAGENTA,
            ColorToken.ANSI_BRIGHT_CYAN, ColorToken.ANSI_BRIGHT_WHITE ->
                ansi[token.ordinal - ColorToken.ANSI_BLACK.ordinal]
        }
    }
}
