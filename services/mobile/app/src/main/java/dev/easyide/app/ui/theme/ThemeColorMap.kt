package dev.easyide.app.ui.theme

/**
 * The only translation from VS Code workbench colour ids to [ColorToken]s
 * (docs/extension-sdk/lld/customization.md sec 8.2). Keys come from VS Code's
 * theme colour reference; any `colors` key not listed here is reported as
 * unmapped rather than guessed at.
 *
 * Several VS Code ids can feed one token (e.g. `sideBar.background` and
 * `panel.background` both paint [ColorToken.PANEL]). When a theme sets more than
 * one of them, the one **later in this table** wins, independent of the order
 * in the theme file - so the result is deterministic and the more specific id
 * is placed last.
 */
object ThemeColorMap {

    val BY_VSCODE_KEY: Map<String, ColorToken> = linkedMapOf(
        "editor.background" to ColorToken.EDITOR_BACKGROUND,
        "editorGutter.background" to ColorToken.GUTTER_BACKGROUND,
        "panel.background" to ColorToken.PANEL,
        "sideBar.background" to ColorToken.PANEL,
        "editorGroupHeader.tabsBackground" to ColorToken.RAISED,
        "menu.background" to ColorToken.OVERLAY,
        "quickInput.background" to ColorToken.OVERLAY,
        "editorWidget.background" to ColorToken.OVERLAY,
        // High-contrast themes draw every border with contrastBorder; the
        // region-specific borders refine it when present.
        "contrastBorder" to ColorToken.HAIRLINE,
        "editorGroup.border" to ColorToken.HAIRLINE,
        "sideBar.border" to ColorToken.HAIRLINE,
        "panel.border" to ColorToken.HAIRLINE,
        "activityBar.background" to ColorToken.ACTIVITY_BAR,
        "statusBar.background" to ColorToken.STATUS_BAR,
        "tab.activeBackground" to ColorToken.TAB_ACTIVE,
        "tab.inactiveBackground" to ColorToken.TAB_INACTIVE,

        "foreground" to ColorToken.FOREGROUND,
        "editor.foreground" to ColorToken.EDITOR_FOREGROUND,
        "descriptionForeground" to ColorToken.TEXT_MUTED,
        "disabledForeground" to ColorToken.TEXT_DISABLED,
        "editorLineNumber.foreground" to ColorToken.LINE_NUMBER,
        "editorLineNumber.activeForeground" to ColorToken.LINE_NUMBER_ACTIVE,
        "statusBar.foreground" to ColorToken.STATUS_BAR_FOREGROUND,
        "tab.activeForeground" to ColorToken.TAB_ACTIVE_FOREGROUND,
        "tab.inactiveForeground" to ColorToken.TAB_INACTIVE_FOREGROUND,
        "activityBar.foreground" to ColorToken.ACTIVITY_BAR_FOREGROUND,
        "activityBar.inactiveForeground" to ColorToken.ACTIVITY_BAR_INACTIVE_FOREGROUND,

        "progressBar.background" to ColorToken.ACCENT,
        "button.background" to ColorToken.ACCENT,
        "button.foreground" to ColorToken.ON_ACCENT,
        "focusBorder" to ColorToken.FOCUS_BORDER,
        "tab.activeBorderTop" to ColorToken.TAB_ACTIVE_BORDER,
        "activityBar.activeBorder" to ColorToken.ACTIVITY_BAR_ACTIVE_BORDER,
        "editorCursor.foreground" to ColorToken.CURSOR,
        "editor.selectionBackground" to ColorToken.SELECTION,
        "editor.lineHighlightBackground" to ColorToken.CURRENT_LINE,
        "editorBracketMatch.background" to ColorToken.BRACKET_MATCH,
        "list.activeSelectionBackground" to ColorToken.LIST_ACTIVE_SELECTION,
        "list.activeSelectionForeground" to ColorToken.LIST_ACTIVE_SELECTION_FOREGROUND,
        "tree.indentGuidesStroke" to ColorToken.INDENT_GUIDE,

        "errorForeground" to ColorToken.ERROR,
        "notificationsWarningIcon.foreground" to ColorToken.WARNING,
        "notificationsInfoIcon.foreground" to ColorToken.INFO,
        "testing.iconPassed" to ColorToken.SUCCESS,

        "gitDecoration.addedResourceForeground" to ColorToken.GIT_ADDED,
        "gitDecoration.modifiedResourceForeground" to ColorToken.GIT_MODIFIED,
        "gitDecoration.deletedResourceForeground" to ColorToken.GIT_DELETED,
        "gitDecoration.conflictingResourceForeground" to ColorToken.GIT_CONFLICT,
        "gitDecoration.untrackedResourceForeground" to ColorToken.GIT_UNTRACKED,
        // VS Code's graph has five lane colours; lanes 6-8 keep the base palette.
        "scmGraph.foreground1" to ColorToken.LANE_1,
        "scmGraph.foreground2" to ColorToken.LANE_2,
        "scmGraph.foreground3" to ColorToken.LANE_3,
        "scmGraph.foreground4" to ColorToken.LANE_4,
        "scmGraph.foreground5" to ColorToken.LANE_5,

        "editorError.foreground" to ColorToken.DIAGNOSTIC_ERROR,
        "editorWarning.foreground" to ColorToken.DIAGNOSTIC_WARNING,
        "editorInfo.foreground" to ColorToken.DIAGNOSTIC_INFORMATION,
        "editorHint.foreground" to ColorToken.DIAGNOSTIC_HINT,
        "editor.wordHighlightTextBackground" to ColorToken.HIGHLIGHT_TEXT,
        "editor.wordHighlightBackground" to ColorToken.HIGHLIGHT_READ,
        "editor.wordHighlightStrongBackground" to ColorToken.HIGHLIGHT_WRITE,
        "editor.findMatchHighlightBackground" to ColorToken.SEARCH_MATCH,
        "editor.findMatchBackground" to ColorToken.SEARCH_MATCH_CURRENT,
        "editorInlayHint.foreground" to ColorToken.INLAY_HINT_FOREGROUND,
        "editorInlayHint.background" to ColorToken.INLAY_HINT_BACKGROUND,
        "editorLightBulb.foreground" to ColorToken.LIGHTBULB,
        "editorCodeLens.foreground" to ColorToken.CODE_LENS,

        "terminal.foreground" to ColorToken.TERMINAL_FOREGROUND,
        "terminal.background" to ColorToken.TERMINAL_BACKGROUND,
        "terminalCursor.foreground" to ColorToken.TERMINAL_CURSOR,
        "terminal.ansiBlack" to ColorToken.ANSI_BLACK,
        "terminal.ansiRed" to ColorToken.ANSI_RED,
        "terminal.ansiGreen" to ColorToken.ANSI_GREEN,
        "terminal.ansiYellow" to ColorToken.ANSI_YELLOW,
        "terminal.ansiBlue" to ColorToken.ANSI_BLUE,
        "terminal.ansiMagenta" to ColorToken.ANSI_MAGENTA,
        "terminal.ansiCyan" to ColorToken.ANSI_CYAN,
        "terminal.ansiWhite" to ColorToken.ANSI_WHITE,
        "terminal.ansiBrightBlack" to ColorToken.ANSI_BRIGHT_BLACK,
        "terminal.ansiBrightRed" to ColorToken.ANSI_BRIGHT_RED,
        "terminal.ansiBrightGreen" to ColorToken.ANSI_BRIGHT_GREEN,
        "terminal.ansiBrightYellow" to ColorToken.ANSI_BRIGHT_YELLOW,
        "terminal.ansiBrightBlue" to ColorToken.ANSI_BRIGHT_BLUE,
        "terminal.ansiBrightMagenta" to ColorToken.ANSI_BRIGHT_MAGENTA,
        "terminal.ansiBrightCyan" to ColorToken.ANSI_BRIGHT_CYAN,
        "terminal.ansiBrightWhite" to ColorToken.ANSI_BRIGHT_WHITE,
    )

    /**
     * Tokens that follow another token when a theme sets the source but not
     * them, mirroring VS Code's own colour defaults (a theme that only sets
     * `editor.background` gets a matching gutter and active tab, not our
     * graphite ones beside its colour). Each pair is `target to source`, ordered
     * so a source is final before anything derives from it.
     */
    val DERIVED: List<Pair<ColorToken, ColorToken>> = listOf(
        ColorToken.EDITOR_FOREGROUND to ColorToken.FOREGROUND,
        ColorToken.GUTTER_BACKGROUND to ColorToken.EDITOR_BACKGROUND,
        ColorToken.TAB_ACTIVE to ColorToken.EDITOR_BACKGROUND,
        ColorToken.TERMINAL_BACKGROUND to ColorToken.EDITOR_BACKGROUND,
        ColorToken.LINE_NUMBER_ACTIVE to ColorToken.EDITOR_FOREGROUND,
        ColorToken.TAB_ACTIVE_FOREGROUND to ColorToken.EDITOR_FOREGROUND,
        ColorToken.TERMINAL_FOREGROUND to ColorToken.EDITOR_FOREGROUND,
        ColorToken.TERMINAL_CURSOR to ColorToken.CURSOR,
        ColorToken.TAB_ACTIVE_BORDER to ColorToken.FOCUS_BORDER,
        ColorToken.ACTIVITY_BAR_ACTIVE_BORDER to ColorToken.FOCUS_BORDER,
        ColorToken.INDENT_GUIDE to ColorToken.HAIRLINE,
        ColorToken.GIT_UNTRACKED to ColorToken.GIT_ADDED,
    )
}
