package dev.easyide.app.ui.screens.workspace.layout

/** The `terminal.accessoryBar` setting: when the terminal's on-screen key row shows. */
enum class AccessoryBarMode {
    /** Hidden while a hardware keyboard is attached, shown otherwise. */
    AUTO,
    ALWAYS,
    NEVER;

    fun isVisible(hardwareKeyboardAttached: Boolean): Boolean = when (this) {
        AUTO -> !hardwareKeyboardAttached
        ALWAYS -> true
        NEVER -> false
    }
}
