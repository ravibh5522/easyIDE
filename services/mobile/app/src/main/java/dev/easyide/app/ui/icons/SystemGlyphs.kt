package dev.easyide.app.ui.icons

/* System group: the runtime the code runs in and how it is extended. Same rules as [EiGlyph]. */

/** Corner brackets around a box. Depicts a container, not a security boundary (repo rule). */
internal val SANDBOX = EiGlyph(
    "sandbox",
    outline = "M7 3H3V7M17 3H21V7M21 17V21H17M7 21H3V17M9 9H15V15H9Z",
)

/** Three chevrons stacked, each lower one resting inside the last. */
internal val ENVIRONMENT = EiGlyph("environment", outline = "M6 6L12 12L18 6M6 11L12 17L18 11M6 16L12 22L18 16")

/** An upright `#`: the root prompt. */
internal val ROOT = EiGlyph("root", outline = "M9 4V20M15 4V20M4 9H20M4 15H20")

/** A brick with two studs. */
internal val EXTENSION_PACK = EiGlyph("extension_pack", outline = "M4 10H20V20H4ZM7 10V6H10V10M14 10V6H17V10")

/** An arrow down onto a base line. */
internal val INSTALL = EiGlyph("install", outline = "M12 3V15M7 10L12 15L17 10M4 20H20")

/** A square with its left half filled. */
internal val THEME = EiGlyph("theme", outline = "M4 4H20V20H4Z", fill = "M4 4h8v16H4z")

internal val SYSTEM_GLYPHS = listOf(SANDBOX, ENVIRONMENT, ROOT, EXTENSION_PACK, INSTALL, THEME)
