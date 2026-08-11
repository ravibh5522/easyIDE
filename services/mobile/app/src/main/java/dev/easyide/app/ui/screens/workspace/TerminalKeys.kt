package dev.easyide.app.ui.screens.workspace

/**
 * One key on the terminal's accessory row: a label to show, and the bytes it
 * writes straight to the shell.
 *
 * A soft keyboard has no arrows, and buries `| ~ / $ > &` two layers deep -
 * which is most of what shell work is made of. This row puts them one tap
 * away. With a real pty ([TerminalPane]), `^C` is an actual `SIGINT` the
 * shell's own line discipline delivers, `↑`/`↓` are the VT100 sequences the
 * shell's own readline already recognises as history - there is no
 * ViewModel-side text buffer or history list to keep in sync with any of it.
 */
data class TerminalKey(val label: String, val bytes: String = label)

/**
 * The accessory row, in order. Single declarative source: nothing else builds
 * terminal keys, so adding one is a line here rather than a change in the UI.
 *
 * Navigation first (fixed position, so muscle memory works), then the symbols
 * roughly by how often a shell needs them.
 */
object TerminalKeyboard {

    private const val ESC = "\u001B"
    private const val CTRL_C = "\u0003"
    // Ctrl-L: the readline binding for "clear screen" in every shell that
    // matters, so this behaves exactly as if the user typed it.
    private const val CTRL_L = "\u000C"

    val KEYS: List<TerminalKey> = listOf(
        TerminalKey("^C", CTRL_C),
        TerminalKey("↑", "$ESC[A"), // up
        TerminalKey("↓", "$ESC[B"), // down
        TerminalKey("←", "$ESC[D"), // left
        TerminalKey("→", "$ESC[C"), // right
        TerminalKey("clr", CTRL_L),
        TerminalKey("Tab", "\t"),
        TerminalKey("Esc", ESC),
        TerminalKey("-"),
        TerminalKey("_"),
        TerminalKey("/"),
        TerminalKey("~"),
        TerminalKey("."),
        TerminalKey("|"),
        TerminalKey("$"),
        TerminalKey("*"),
        TerminalKey("&"),
        TerminalKey(">"),
        TerminalKey("<"),
        TerminalKey("\""),
        TerminalKey("'"),
        TerminalKey("("),
        TerminalKey(")"),
        TerminalKey("["),
        TerminalKey("]"),
        TerminalKey("{"),
        TerminalKey("}"),
        TerminalKey("="),
        TerminalKey(";"),
        TerminalKey("#"),
        TerminalKey("!"),
        TerminalKey("\\"),
    )
}
