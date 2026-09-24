package dev.easyide.app.ui.icons

/* Git group: square nodes and straight rails, never rounded dots. Same rules as [EiGlyph]. */

/** A trunk with a side line leaving it at 45 degrees to a second node. */
internal val BRANCH = EiGlyph(
    "branch",
    outline = "M5 3H9V7H5ZM5 17H9V21H5ZM15 5H19V9H15ZM7 7V17M7 15L10 12H17V9",
)

/** One rail passing through a node. */
internal val COMMIT = EiGlyph("commit", outline = "M2 12H8M16 12H22M8 8H16V16H8Z")

/** Two sides split on the diagonal: added above, removed below. */
internal val DIFF = EiGlyph("diff", outline = "M3 3H21V21H3ZM21 3L3 21M5 8H11M8 5V11M13 16H19")

/** An arrow going into an open tray. */
internal val STASH = EiGlyph("stash", outline = "M3 12V20H21V12M12 3V13M8 9L12 13L16 9")

/** Two opposite arrows: local and remote exchanging. */
internal val SYNC = EiGlyph("sync", outline = "M4 8H20M16 4L20 8L16 12M20 16H4M8 12L4 16L8 20")

internal val GIT_GLYPHS = listOf(BRANCH, COMMIT, DIFF, STASH, SYNC)
