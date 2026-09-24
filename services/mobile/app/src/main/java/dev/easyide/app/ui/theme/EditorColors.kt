package dev.easyide.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Workspace colours: the editor, its chrome (tabs, tree, rail, status bar) and
 * the terminal. The IDE surfaces want a denser, flatter palette than Material's
 * tonal surfaces give - near-uniform backgrounds so syntax colours carry the
 * contrast, not the panels.
 *
 * Not a palette of its own: every field is read from [ThemeTokens] by
 * [toEditorColors], the same tokens Material's ColorScheme is built from, so the
 * workspace and the native screens always share one palette (decision 0019).
 */
@Immutable
data class EditorColors(
    val background: Color,
    val gutter: Color,
    /** Line numbers. */
    val gutterText: Color,
    /** The caret line's number. */
    val gutterActiveText: Color,
    val panel: Color,
    val raised: Color,
    val overlay: Color,
    /** Hairline between regions, where tonal separation is not enough. */
    val panelBorder: Color,
    val activityBar: Color,
    val activityIcon: Color,
    val activityIconActive: Color,
    val activityIndicator: Color,
    val statusBar: Color,
    val statusBarText: Color,
    val tabActive: Color,
    val tabInactive: Color,
    val tabActiveText: Color,
    val tabInactiveText: Color,
    /** The accent bar across the top of the active tab. */
    val tabActiveBorder: Color,
    val plainText: Color,
    /** Secondary chrome text: section headers, paths, inactive labels. */
    val textMuted: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    val focus: Color,
    val cursor: Color,
    /** Translucent; paints under the selected text. */
    val selection: Color,
    /** Translucent; paints under the caret line. */
    val currentLine: Color,
    /** Box behind the bracket at the caret and its partner. */
    val bracketMatch: Color,
    /** Opaque pill behind the selected tree row. */
    val listSelection: Color,
    val listSelectionText: Color,
    val indentGuide: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val success: Color,
    val git: GitColors,
    /** Categorical commit-graph lane colours; the graph cycles through them by lane index. */
    val lanes: List<Color>,
    val syntax: SyntaxColors,
    /** Squiggles, highlights, find matches, inlay hints and gutter icons. */
    val decorations: DecorationColors,
    val terminal: TerminalPalette,
    /** Semantic token styling of the active theme and `editor.semanticTokenColorCustomizations`. */
    val semantic: SemanticTokenColors = SemanticTokenColors.BUILT_IN,
)

/** Working-tree state colours, shared by source control rows and (later) tree badges. */
@Immutable
data class GitColors(
    val added: Color,
    val modified: Color,
    val deleted: Color,
    val conflict: Color,
    val untracked: Color,
)

/**
 * What the terminal emulator paints with: default fg/bg/cursor plus the 16
 * ANSI slots in SGR order. Handed to the vendored Termux emulator by
 * `TerminalTheme`; colours 16-255 stay the xterm cube the emulator ships.
 */
@Immutable
data class TerminalPalette(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val ansi: List<Color>,
)

/** The workspace view of a token set. Pure; cheap enough to run on every theme change. */
fun ThemeTokens.toEditorColors(): EditorColors = EditorColors(
    background = this[ColorToken.EDITOR_BACKGROUND],
    gutter = this[ColorToken.GUTTER_BACKGROUND],
    gutterText = this[ColorToken.LINE_NUMBER],
    gutterActiveText = this[ColorToken.LINE_NUMBER_ACTIVE],
    panel = this[ColorToken.PANEL],
    raised = this[ColorToken.RAISED],
    overlay = this[ColorToken.OVERLAY],
    panelBorder = this[ColorToken.HAIRLINE],
    activityBar = this[ColorToken.ACTIVITY_BAR],
    activityIcon = this[ColorToken.ACTIVITY_BAR_INACTIVE_FOREGROUND],
    activityIconActive = this[ColorToken.ACTIVITY_BAR_FOREGROUND],
    activityIndicator = this[ColorToken.ACTIVITY_BAR_ACTIVE_BORDER],
    statusBar = this[ColorToken.STATUS_BAR],
    statusBarText = this[ColorToken.STATUS_BAR_FOREGROUND],
    tabActive = this[ColorToken.TAB_ACTIVE],
    tabInactive = this[ColorToken.TAB_INACTIVE],
    tabActiveText = this[ColorToken.TAB_ACTIVE_FOREGROUND],
    tabInactiveText = this[ColorToken.TAB_INACTIVE_FOREGROUND],
    tabActiveBorder = this[ColorToken.TAB_ACTIVE_BORDER],
    plainText = this[ColorToken.EDITOR_FOREGROUND],
    textMuted = this[ColorToken.TEXT_MUTED],
    textDisabled = this[ColorToken.TEXT_DISABLED],
    accent = this[ColorToken.ACCENT],
    onAccent = this[ColorToken.ON_ACCENT],
    focus = this[ColorToken.FOCUS_BORDER],
    cursor = this[ColorToken.CURSOR],
    selection = this[ColorToken.SELECTION],
    currentLine = this[ColorToken.CURRENT_LINE],
    bracketMatch = this[ColorToken.BRACKET_MATCH],
    listSelection = this[ColorToken.LIST_ACTIVE_SELECTION],
    listSelectionText = this[ColorToken.LIST_ACTIVE_SELECTION_FOREGROUND],
    indentGuide = this[ColorToken.INDENT_GUIDE],
    error = this[ColorToken.ERROR],
    warning = this[ColorToken.WARNING],
    info = this[ColorToken.INFO],
    success = this[ColorToken.SUCCESS],
    git = GitColors(
        added = this[ColorToken.GIT_ADDED],
        modified = this[ColorToken.GIT_MODIFIED],
        deleted = this[ColorToken.GIT_DELETED],
        conflict = this[ColorToken.GIT_CONFLICT],
        untracked = this[ColorToken.GIT_UNTRACKED],
    ),
    lanes = ColorToken.LANES.map { this[it] },
    syntax = syntax,
    decorations = DecorationColors(
        diagnosticError = this[ColorToken.DIAGNOSTIC_ERROR],
        diagnosticWarning = this[ColorToken.DIAGNOSTIC_WARNING],
        diagnosticInformation = this[ColorToken.DIAGNOSTIC_INFORMATION],
        diagnosticHint = this[ColorToken.DIAGNOSTIC_HINT],
        highlightText = this[ColorToken.HIGHLIGHT_TEXT],
        highlightRead = this[ColorToken.HIGHLIGHT_READ],
        highlightWrite = this[ColorToken.HIGHLIGHT_WRITE],
        searchMatch = this[ColorToken.SEARCH_MATCH],
        searchMatchCurrent = this[ColorToken.SEARCH_MATCH_CURRENT],
        inlayHintText = this[ColorToken.INLAY_HINT_FOREGROUND],
        inlayHintBackground = this[ColorToken.INLAY_HINT_BACKGROUND],
        lightbulb = this[ColorToken.LIGHTBULB],
        codeLens = this[ColorToken.CODE_LENS],
    ),
    terminal = TerminalPalette(
        foreground = this[ColorToken.TERMINAL_FOREGROUND],
        background = this[ColorToken.TERMINAL_BACKGROUND],
        cursor = this[ColorToken.TERMINAL_CURSOR],
        ansi = ColorToken.ANSI.map { this[it] },
    ),
    semantic = semantic,
)

/** Provided by [EasyIdeTheme]; the default only serves previews and tests without a theme. */
val LocalEditorColors = staticCompositionLocalOf { GraphiteDarkPalette.toTokens().toEditorColors() }

/** Convenience for composables that only need the current set. */
val editorColors: EditorColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEditorColors.current

/** Kept so callers can reach Material tokens alongside editor tokens. */
val materialColors
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme
