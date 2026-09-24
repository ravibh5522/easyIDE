package dev.easyide.app.ui.icons

/* Work group: what a maker opens and runs. Same grid and rules as [EiGlyph]. */

private const val FOLDER = "M3 5H10L12 7H21V19H3Z"

/** A folder with a filled tick at its corner: a project, not just a directory. */
internal val PROJECT = EiGlyph("project", outline = FOLDER, fill = "M17 15h4v4h-4z")

internal val NEW_PROJECT = EiGlyph("new_project", outline = "${FOLDER}M12 10V16M9 13H15")

/** A window with a prompt line in it. */
internal val TERMINAL = EiGlyph("terminal", outline = "M3 5H21V19H3ZM7 9L10 12L7 15M12 15H17")

/** Braces around a filled dot: a server answering. */
internal val LANGUAGE_SERVER = EiGlyph(
    "language_server",
    outline = "M9 4H8L6 6V10L4 12L6 14V18L8 20H9M15 4H16L18 6V10L20 12L18 14V18L16 20H15",
    fill = "M11 11h2v2h-2z",
)

/** A chevron and a spark: something that acts on the prompt's behalf. */
internal val AGENT = EiGlyph(
    "agent",
    outline = "M4 7L9 12L4 17M16 8V16M12 12H20M13 9L19 15M13 15L19 9",
)

internal val WORK_GLYPHS = listOf(PROJECT, NEW_PROJECT, TERMINAL, LANGUAGE_SERVER, AGENT)
