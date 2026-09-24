package dev.easyide.app.ui.theme

/**
 * Every named colour in easyIDE's one token system (decision 0019).
 *
 * Chrome ([androidx.compose.material3.ColorScheme]), the editor, the terminal
 * and the decoration painters all read from the same [ThemeTokens] keyed by
 * these names, so a theme - built in or, later, a VS Code colour theme mapped
 * through [ThemeColorMap] - recolours every surface at once. Syntax colours are
 * not here: they are keyed by [SyntaxRole] in [SyntaxColors], because
 * `tokenColors` collapse onto roles, not onto workbench keys.
 *
 * Grouped by what reads them. The VS Code key each token answers to lives in
 * [ThemeColorMap] and nowhere else.
 */
enum class ColorToken {
    // Surfaces, in tonal order: editor < panel < raised < overlay.
    EDITOR_BACKGROUND,
    GUTTER_BACKGROUND,
    PANEL,
    RAISED,
    OVERLAY,
    HAIRLINE,
    ACTIVITY_BAR,
    STATUS_BAR,
    TAB_ACTIVE,
    TAB_INACTIVE,

    // Content.
    FOREGROUND,
    EDITOR_FOREGROUND,
    TEXT_MUTED,
    TEXT_DISABLED,
    LINE_NUMBER,
    LINE_NUMBER_ACTIVE,
    STATUS_BAR_FOREGROUND,
    TAB_ACTIVE_FOREGROUND,
    TAB_INACTIVE_FOREGROUND,
    ACTIVITY_BAR_FOREGROUND,
    ACTIVITY_BAR_INACTIVE_FOREGROUND,

    // The one accent and everything that marks focus with it.
    ACCENT,
    ON_ACCENT,
    FOCUS_BORDER,
    TAB_ACTIVE_BORDER,
    ACTIVITY_BAR_ACTIVE_BORDER,
    CURSOR,
    SELECTION,
    CURRENT_LINE,
    BRACKET_MATCH,
    LIST_ACTIVE_SELECTION,
    LIST_ACTIVE_SELECTION_FOREGROUND,
    INDENT_GUIDE,

    // Status signals for chrome (banners, badges, error rows).
    ERROR,
    WARNING,
    INFO,
    SUCCESS,

    // Source control: file state, and the categorical commit-graph lanes.
    GIT_ADDED,
    GIT_MODIFIED,
    GIT_DELETED,
    GIT_CONFLICT,
    GIT_UNTRACKED,
    LANE_1,
    LANE_2,
    LANE_3,
    LANE_4,
    LANE_5,
    LANE_6,
    LANE_7,
    LANE_8,

    // Editor decorations (see DecorationColors).
    DIAGNOSTIC_ERROR,
    DIAGNOSTIC_WARNING,
    DIAGNOSTIC_INFORMATION,
    DIAGNOSTIC_HINT,
    HIGHLIGHT_TEXT,
    HIGHLIGHT_READ,
    HIGHLIGHT_WRITE,
    SEARCH_MATCH,
    SEARCH_MATCH_CURRENT,
    INLAY_HINT_FOREGROUND,
    INLAY_HINT_BACKGROUND,
    LIGHTBULB,
    CODE_LENS,

    // Terminal: default colours plus the 16 ANSI slots, in SGR order 0-15.
    TERMINAL_FOREGROUND,
    TERMINAL_BACKGROUND,
    TERMINAL_CURSOR,
    ANSI_BLACK,
    ANSI_RED,
    ANSI_GREEN,
    ANSI_YELLOW,
    ANSI_BLUE,
    ANSI_MAGENTA,
    ANSI_CYAN,
    ANSI_WHITE,
    ANSI_BRIGHT_BLACK,
    ANSI_BRIGHT_RED,
    ANSI_BRIGHT_GREEN,
    ANSI_BRIGHT_YELLOW,
    ANSI_BRIGHT_BLUE,
    ANSI_BRIGHT_MAGENTA,
    ANSI_BRIGHT_CYAN,
    ANSI_BRIGHT_WHITE,
    ;

    companion object {
        /** Lane tokens in drawing order; the graph cycles through them by lane index. */
        val LANES: List<ColorToken> = listOf(LANE_1, LANE_2, LANE_3, LANE_4, LANE_5, LANE_6, LANE_7, LANE_8)

        /** ANSI tokens in SGR index order, so `ANSI[i]` is terminal colour `i`. */
        val ANSI: List<ColorToken> = entries.subList(ANSI_BLACK.ordinal, ANSI_BRIGHT_WHITE.ordinal + 1)
    }
}
