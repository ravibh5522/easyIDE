package dev.easyide.app.ui.shell.workspace

/** Which text input owns focus: the dock offers keys for the editor and for the terminal, and nothing for any other field. */
enum class DockFocus { NONE, EDITOR, TERMINAL }

/**
 * When the input dock shows (shell-model.md section 9). A hardware keyboard needs no on-screen
 * keys. On a phone the dock exists only while the software keyboard is up (it takes the bottom
 * bar's place); on a wider window there is no bar to replace, so it stays with the focused input.
 */
object DockRules {
    fun visible(focus: DockFocus, keyboardUp: Boolean, hardwareKeyboard: Boolean, compact: Boolean): Boolean =
        focus != DockFocus.NONE && !hardwareKeyboard && (keyboardUp || !compact)
}
