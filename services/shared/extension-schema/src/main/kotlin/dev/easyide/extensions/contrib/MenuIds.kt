package dev.easyide.extensions.contrib

/** sdk-reference menu ids, including the tablet menus (EXT-33). Unknown ids load with a warning. */
object MenuIds {
    const val COMMAND_PALETTE = "commandPalette"
    const val EDITOR_TITLE = "editor/title"
    const val EDITOR_TITLE_CONTEXT = "editor/title/context"
    const val EDITOR_CONTEXT = "editor/context"
    const val EDITOR_TOUCH_TOOLBAR = "editor/touchToolbar"
    const val EDITOR_SELECTION_TOOLBAR = "editor/selectionToolbar"
    const val EDITOR_GUTTER = "editor/gutter"
    const val EXPLORER_CONTEXT = "explorer/context"
    const val VIEW_TITLE = "view/title"
    const val VIEW_ITEM_CONTEXT = "view/item/context"
    const val SCM_TITLE = "scm/title"
    const val SCM_RESOURCE_STATE_CONTEXT = "scm/resourceState/context"
    const val TERMINAL_CONTEXT = "terminal/context"
    const val TERMINAL_TITLE = "terminal/title"
    const val STATUS_BAR = "statusBar"
    const val KEY_ROW = "keyRow"

    val ALL: Set<String> = setOf(
        COMMAND_PALETTE, EDITOR_TITLE, EDITOR_TITLE_CONTEXT, EDITOR_CONTEXT, EDITOR_TOUCH_TOOLBAR,
        EDITOR_SELECTION_TOOLBAR, EDITOR_GUTTER, EXPLORER_CONTEXT, VIEW_TITLE, VIEW_ITEM_CONTEXT, SCM_TITLE,
        SCM_RESOURCE_STATE_CONTEXT, TERMINAL_CONTEXT, TERMINAL_TITLE, STATUS_BAR, KEY_ROW,
    )

    /** VS Code's group order: `navigation` first, then by group name, then `@order`, then title. */
    const val NAVIGATION_GROUP = "navigation"
}
