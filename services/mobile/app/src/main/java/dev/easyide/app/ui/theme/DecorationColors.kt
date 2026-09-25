package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours of editor decorations (squiggles, highlights, matches, inlay hints, gutter icons).
 *
 * A projection of [ThemeTokens] (built by [toEditorColors]): the built-in values derive from
 * each [Palette]'s signal colours, and each field names the VS Code theme colour id that
 * [ThemeColorMap] maps onto its token (ids verified 2026-09-24 from microsoft/vscode
 * `editorColors.ts`, `editorColorRegistry.ts`, `highlightDecorations.ts`), so an imported
 * theme recolours squiggles one to one. Highlight and match backgrounds are translucent on
 * purpose: they paint under the text and over each other.
 */
data class DecorationColors(
    /** `editorError.foreground` */
    val diagnosticError: Color,
    /** `editorWarning.foreground` */
    val diagnosticWarning: Color,
    /** `editorInfo.foreground` */
    val diagnosticInformation: Color,
    /** `editorHint.foreground` */
    val diagnosticHint: Color,
    /** `editor.wordHighlightTextBackground` */
    val highlightText: Color,
    /** `editor.wordHighlightBackground` */
    val highlightRead: Color,
    /** `editor.wordHighlightStrongBackground` */
    val highlightWrite: Color,
    /** `editor.findMatchHighlightBackground` */
    val searchMatch: Color,
    /** `editor.findMatchBackground` */
    val searchMatchCurrent: Color,
    /** `editor.findMatchBorder`: outlines the current match */
    val searchMatchCurrentBorder: Color,
    /** `editorInlayHint.foreground` */
    val inlayHintText: Color,
    /** `editorInlayHint.background` (`badge.background` at 10%) */
    val inlayHintBackground: Color,
    /** `editorLightBulb.foreground` */
    val lightbulb: Color,
    /** `editorCodeLens.foreground` */
    val codeLens: Color,
)
