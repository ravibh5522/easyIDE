package dev.easyide.app.ui.kit

import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.Motion

/** The pieces of the Block identity a surface may draw (identity.md 2). */
enum class MotifElement {
    /** The block cursor as state: a marker, a caret, a sweep. Functional, so drawn at every motif level. */
    CursorState,

    /** Cell-fill progress. Functional as well. */
    CellFill,
    PromptGlyph,
    CropCorners,
    TypedTitle,

    /** Ruler ticks on a hairline: full level only. */
    Ticks,

    /** Box-drawing empty-state art: full level only. */
    Art,
}

/**
 * The per-surface motif table of identity.md 2.3 as data: which elements a surface may draw and,
 * for a blinking cursor, how many cycles it is allowed ([cursorCycles], null for a caret or a
 * loading sweep that lives as long as the state does). A surface shows one to three instances and
 * dialogs none, which the table's own test enforces, so a screen cannot drift into costume.
 */
enum class MotifSurface(val cursorCycles: Int?, val elements: Set<MotifElement>) {
    Home(Motion.HEADER_BLINK_CYCLES, setOf(MotifElement.CursorState, MotifElement.PromptGlyph, MotifElement.CropCorners)),
    Settings(null, setOf(MotifElement.CursorState)),
    Extensions(null, setOf(MotifElement.CellFill)),
    Onboarding(null, setOf(MotifElement.CropCorners, MotifElement.TypedTitle, MotifElement.Ticks)),
    Workspace(null, setOf(MotifElement.CursorState)),
    Dialog(null, emptySet()),
    Loading(null, setOf(MotifElement.CursorState)),
    EmptyState(Motion.HEADER_BLINK_CYCLES, setOf(MotifElement.CursorState, MotifElement.PromptGlyph, MotifElement.Art)),
    ;

    /** True when this surface draws [element] under `appearance.motif` = [level]. */
    fun allows(element: MotifElement, level: Motif): Boolean = element in elements && when (element) {
        MotifElement.CursorState, MotifElement.CellFill -> true
        MotifElement.Ticks, MotifElement.Art -> level.drawsArt
        else -> level.drawsSupporting
    }
}
