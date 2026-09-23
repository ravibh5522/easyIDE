package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours of editor decorations (squiggles, highlights, matches, inlay hints, gutter icons).
 *
 * Values are VS Code's registered defaults for the matching theme colour ids (verified
 * 2026-09-24 from microsoft/vscode `editorColors.ts`, `editorColorRegistry.ts`,
 * `highlightDecorations.ts`), so squiggle and highlight colours read the way VS Code users
 * expect and a future theme import (ux-overhaul ADR-C) maps ids one to one. Highlight and
 * match backgrounds are translucent on purpose: they paint under the text and over each other.
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
    /** `editorInlayHint.foreground` */
    val inlayHintText: Color,
    /** `editorInlayHint.background` (`badge.background` at 10%) */
    val inlayHintBackground: Color,
    /** `editorLightBulb.foreground` */
    val lightbulb: Color,
    /** `editorCodeLens.foreground` */
    val codeLens: Color,
)

val DarkDecorationColors = DecorationColors(
    diagnosticError = Color(0xFFF14C4C),
    diagnosticWarning = Color(0xFFCCA700),
    diagnosticInformation = Color(0xFF59A4F9),
    diagnosticHint = Color(0xB3EEEEEE),
    highlightText = Color(0xB8575757),
    highlightRead = Color(0xB8575757),
    highlightWrite = Color(0xB8004972),
    searchMatch = Color(0x55EA5C00),
    searchMatchCurrent = Color(0xFF515C6A),
    inlayHintText = Color(0xFF969696),
    inlayHintBackground = Color(0x1A4D4D4D),
    lightbulb = Color(0xFFFFCC00),
    codeLens = Color(0xFF999999),
)

val LightDecorationColors = DecorationColors(
    diagnosticError = Color(0xFFE51400),
    diagnosticWarning = Color(0xFFBF8803),
    diagnosticInformation = Color(0xFF0063D3),
    diagnosticHint = Color(0xFF6C6C6C),
    highlightText = Color(0x40575757),
    highlightRead = Color(0x40575757),
    highlightWrite = Color(0x400E639C),
    searchMatch = Color(0x55EA5C00),
    searchMatchCurrent = Color(0xFFA8AC94),
    inlayHintText = Color(0xFF969696),
    inlayHintBackground = Color(0x1AC4C4C4),
    lightbulb = Color(0xFFDDB100),
    codeLens = Color(0xFF919191),
)
