package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.ui.unit.dp

/**
 * The one table of timings and limits of the LSP presenters (lsp-features.md sec 7 "LspPolicy
 * additions"). They live in the app because only the presenters use them; like `LspPolicy`
 * they are not settings. Values are starting points to profile on the reference device.
 */
object LspUiPolicy {
    const val SIGNATURE_HELP_DEBOUNCE_MS = 100L
    const val DOCUMENT_HIGHLIGHT_DEBOUNCE_MS = 250L
    const val CODE_ACTION_DEBOUNCE_MS = 250L
    const val VIEWPORT_FEATURES_DEBOUNCE_MS = 300L
    const val WORKSPACE_SYMBOL_DEBOUNCE_MS = 200L
    const val SEMANTIC_TOKENS_DEBOUNCE_MS = 300L

    /** A document longer than this gets `semanticTokens/range` for the viewport before the full set. */
    const val SEMANTIC_RANGE_FIRST_LINES = 2000

    /** Lines above and below the viewport included in viewport requests. */
    const val VIEWPORT_MARGIN_LINES = 50

    /** Semantic tokens kept per document; a server sending more gets the rest ignored. */
    const val MAX_SEMANTIC_TOKENS = 250_000

    /** Code lenses resolved per visible-range pass, so a huge file cannot flood the server. */
    const val MAX_CODE_LENS_RESOLVES = 50

    /** Focus changes this close together resolve only the item the user stopped on. */
    const val COMPLETION_RESOLVE_DEBOUNCE_MS = 60L

    /** Accepting an unresolved item waits this long for `additionalTextEdits` (auto-imports). */
    const val ACCEPT_RESOLVE_TIMEOUT_MS = 300L

    /** Items kept per server after filtering (lsp-features.md `MAX_COMPLETION_ITEMS`). */
    const val MAX_COMPLETION_ITEMS = 500

    /** Recent texts kept per open document so results for an older version can be placed. */
    const val TEXT_HISTORY_VERSIONS = 8

    /** Rows of the completion list shown before it scrolls. */
    const val COMPLETION_VISIBLE_ROWS = 8

    /** PageUp / PageDown step in the completion list. */
    const val COMPLETION_PAGE_ROWS = COMPLETION_VISIBLE_ROWS - 1
}

/** Sizes of the LSP popups and panels, in one place. */
internal object LspUiMetrics {
    /** Least height of a list row (VS Code's suggest rows are 22); a larger font grows it. */
    val completionRowHeight = 22.dp
    val completionWidth = 360.dp
    val completionDocWidth = 320.dp
    val popupMaxHeight = 280.dp
    val hoverMaxWidth = 480.dp
    val kindIconSize = 16.dp
    val rowPaddingH = 6.dp
    val rowPaddingV = 2.dp
    val panelWidth = 320.dp
    val statusIconSize = 14.dp
}
