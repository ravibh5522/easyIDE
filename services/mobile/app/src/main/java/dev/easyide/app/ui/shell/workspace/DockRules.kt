package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.data.settings.ChromeVisibility

/** Which text input owns focus: the dock offers keys for the editor and for the terminal, and nothing for any other field. */
enum class DockFocus { NONE, EDITOR, TERMINAL }

/** What the one dock row carries: the key row, and the touch toolbar's actions behind `more`. */
class DockShow(val keys: Boolean, val actions: Boolean) {
    val any: Boolean get() = keys || actions
}

/**
 * When the input dock shows (shell-model.md section 9). `auto` is for a touch keyboard: the dock
 * exists only while the software keyboard is up and either the window is compact or no hardware
 * keyboard is attached, so a tablet with a keyboard has no row under the editor at all. `always`
 * follows the focused input, `never` hides the part. The toolbar's actions belong to the editor
 * only; a terminal has just its keys.
 */
object DockRules {
    fun visible(mode: ChromeVisibility, focus: DockFocus, keyboardUp: Boolean, hardwareKeyboard: Boolean, compact: Boolean): Boolean = when (mode) {
        ChromeVisibility.NEVER -> false
        ChromeVisibility.ALWAYS -> focus != DockFocus.NONE
        ChromeVisibility.AUTO -> focus != DockFocus.NONE && keyboardUp && (compact || !hardwareKeyboard)
    }

    fun show(keyRow: ChromeVisibility, toolbar: ChromeVisibility, focus: DockFocus, keyboardUp: Boolean, hardwareKeyboard: Boolean, compact: Boolean) = DockShow(
        keys = visible(keyRow, focus, keyboardUp, hardwareKeyboard, compact),
        actions = focus == DockFocus.EDITOR && visible(toolbar, focus, keyboardUp, hardwareKeyboard, compact),
    )
}
