package dev.easyide.app.ui.icons

/*
 * Core group: the marks of the interface itself. Coordinates are centre lines on the 24 grid;
 * see [EiGlyph] for the rules. Comments name what each shape depicts, not how it is drawn.
 */

/** `>_`: a 45-degree chevron and the underscore of a cursor line. */
internal val PROMPT = EiGlyph("prompt", outline = "M5 6L11 12L5 18M13 18H20")

/** The block cursor itself, 0.6 of its height wide (identity.md 2.1). */
internal val CURSOR = EiGlyph("cursor", fill = "M8 5h8v14H8z")

/** An input bar over a list of results. */
internal val PALETTE = EiGlyph(
    "palette",
    outline = "M4 6L6 4H18L20 6V9H4ZM6 13H18M6 17H14",
)

/** Settings as sliders, not a gear: three tracks, each with a block for the handle. */
internal val SETTINGS = EiGlyph(
    "settings",
    outline = "M4 6H20M4 12H20M4 18H20",
    fill = "M14 4h3v4h-3zM7 10h3v4H7zM12 16h3v4h-3z",
)

/** A colour chip: a card with its top band filled and the others ruled off. */
internal val SWATCH = EiGlyph(
    "swatch",
    outline = "M6 3H18V21H6ZM6 9H18M6 15H18",
    fill = "M6 3h12v6H6z",
)

/** A chamfered key with an arrow legend. */
internal val KEYCAP = EiGlyph(
    "keycap",
    outline = "M6 3H18L21 6V18L18 21H6L3 18V6ZM8 14L12 10L16 14",
)

/** Three ruled lines with a vertical measure: how tightly rows are spaced. */
internal val DENSITY = EiGlyph(
    "density",
    outline = "M4 6H14M4 12H14M4 18H14M19 5V19M16.5 7.5L19 5L21.5 7.5M16.5 16.5L19 19L21.5 16.5",
)

/** A pane with its edge bar filled: where a panel docks. */
internal val DOCK = EiGlyph(
    "dock",
    outline = "M3 5H21V19H3Z",
    fill = "M3 15h18v4H3z",
)

internal val CORE_GLYPHS = listOf(PROMPT, CURSOR, PALETTE, SETTINGS, SWATCH, KEYCAP, DENSITY, DOCK)
