package dev.easyide.extensions.view

/**
 * Every hard limit of the UI contribution points (extension-ui.md sections 2.1, 4.4 and 4.5),
 * in one place. No settings layer may relax them: a pack must not be able to stall the UI thread.
 * Static limits are enforced when a manifest or view file is parsed; the rendered and rate limits
 * again at render and update time, because data can grow after a file was validated.
 */
object ViewLimits {
    /** A view schema file (and the `state` object inside it), in UTF-8 bytes. */
    const val MAX_FILE_BYTES = 128 * 1024

    /** Nesting of component nodes, the root being depth 1. */
    const val MAX_DEPTH = 12

    /** Component nodes in one schema file (a list's item template counts once). */
    const val MAX_NODES = 2_000

    /** Nodes drawn at once after data binding; lists virtualize beyond it. */
    const val MAX_RENDERED_NODES = 2_000

    /** Rows a list, tree or table shows however much data it is bound to (paged beyond a screenful). */
    const val MAX_ROWS = 100_000

    /** Rows a screen shows of a virtualized list, for the render budget (the rows drawn at once, not the rows bound). */
    const val VISIBLE_ROWS_ESTIMATE = 40

    /** Rows an inline list realises when it is not the scrolling body of the view. */
    const val MAX_INLINE_ROWS = 100

    /** One string prop or template, in characters. */
    const val MAX_STRING = 4_096

    /** Literal arrays in a schema (`options`, `columns`, `tabs`, `before` effects). */
    const val MAX_LITERAL_LIST = 200

    const val MAX_VIEWS_PER_PACK = 20
    const val MAX_DOCUMENTS_PER_PACK = 10
    const val MAX_CONTAINERS_PER_PACK = 10
    const val MAX_OPENERS_PER_PACK = 20
    const val MAX_PRESETS_PER_PACK = 10
    const val MAX_NAV_ITEMS_PER_PACK = 3

    /** A navigation title is a label under a 24dp icon on a phone. */
    const val NAV_TITLE_MAX = 14

    /** Container, view, document and preset titles. */
    const val TITLE_MAX = 40

    /** Nav `order` values below this belong to built-ins. */
    const val EXTENSION_ORDER_FLOOR = 100

    /** A pack SVG icon (single-colour vector on the 24 grid). */
    const val ICON_MAX_BYTES = 16 * 1024
    const val ICON_GRID = 24

    /** A pack image shown by the `image` component. */
    const val IMAGE_MAX_BYTES = 512 * 1024

    /** `setViewData` from a provider: updates per second per view and bytes per update. */
    const val MAX_UPDATES_PER_SECOND = 10
    const val MAX_UPDATE_BYTES = 256 * 1024

    /** Action-result data sources refresh no faster than this while their view is visible. */
    const val MIN_INTERVAL_SEC = 2

    /** Ring buffers of `logStream` (lines) and `chat` (messages). */
    const val LOG_LINES = 5_000
    const val CHAT_MESSAGES = 2_000

    /** A local state write (`field`, `composer`, `before` effects) longer than this is cut. */
    const val MAX_LOCAL_TEXT = 16_384
}
