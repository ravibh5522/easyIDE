package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * One key on the terminal's accessory row.
 *
 * A soft keyboard has no arrows, and buries `| ~ / $ > &` two layers deep -
 * which is most of what shell work is made of. This row puts them one tap away.
 *
 * Only keys that do something real are listed. Without a PTY there is no way to
 * deliver Esc or Ctrl-<key> *into* the running program, so offering them would
 * be decoration; `^C` stops the foreground command, which is what the key
 * actually means to the person pressing it.
 */
sealed interface TerminalKey {
    val label: String

    /** Types [text] at the caret. */
    data class Insert(override val label: String, val text: String = label) : TerminalKey

    /** Drives the prompt or the running command rather than typing anything. */
    data class Command(override val label: String, val action: TerminalKeyAction) : TerminalKey
}

enum class TerminalKeyAction {
    CARET_LEFT,
    CARET_RIGHT,
    HISTORY_BACK,
    HISTORY_FORWARD,
    INTERRUPT,
    CLEAR,
}

/**
 * The accessory row, in order. Single declarative source: nothing else builds
 * terminal keys, so adding one is a line here rather than a change in the UI.
 *
 * Navigation first (fixed position, so muscle memory works), then the symbols
 * roughly by how often a shell needs them.
 */
object TerminalKeyboard {

    val KEYS: List<TerminalKey> = listOf(
        TerminalKey.Command("^C", TerminalKeyAction.INTERRUPT),
        TerminalKey.Command("↑", TerminalKeyAction.HISTORY_BACK),
        TerminalKey.Command("↓", TerminalKeyAction.HISTORY_FORWARD),
        TerminalKey.Command("←", TerminalKeyAction.CARET_LEFT),
        TerminalKey.Command("→", TerminalKeyAction.CARET_RIGHT),
        TerminalKey.Command("clr", TerminalKeyAction.CLEAR),
        TerminalKey.Insert("-"),
        TerminalKey.Insert("_"),
        TerminalKey.Insert("/"),
        TerminalKey.Insert("~"),
        TerminalKey.Insert("."),
        TerminalKey.Insert("|"),
        TerminalKey.Insert("$"),
        TerminalKey.Insert("*"),
        TerminalKey.Insert("&"),
        TerminalKey.Insert(">"),
        TerminalKey.Insert("<"),
        TerminalKey.Insert("\""),
        TerminalKey.Insert("'"),
        TerminalKey.Insert("("),
        TerminalKey.Insert(")"),
        TerminalKey.Insert("["),
        TerminalKey.Insert("]"),
        TerminalKey.Insert("{"),
        TerminalKey.Insert("}"),
        TerminalKey.Insert("="),
        TerminalKey.Insert(";"),
        TerminalKey.Insert("#"),
        TerminalKey.Insert("!"),
        TerminalKey.Insert("\\"),
    )
}

/**
 * Caret-aware edits to the prompt buffer.
 *
 * The prompt holds a [TextFieldValue] rather than a plain String precisely so
 * these exist: inserting a symbol has to land where the caret is, not at the
 * end, or moving the caret back to fix a typo makes every accessory key wrong.
 */
internal object TerminalInput {

    /** Types [text] over the selection, leaving the caret after it. */
    fun insert(value: TextFieldValue, text: String): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        return TextFieldValue(
            text = value.text.replaceRange(start, end, text),
            selection = TextRange(start + text.length),
        )
    }

    fun moveCaret(value: TextFieldValue, delta: Int): TextFieldValue {
        val caret = (value.selection.start + delta).coerceIn(0, value.text.length)
        return value.copy(selection = TextRange(caret))
    }

    /** Caret at the end, which is where recalling a command should leave it. */
    fun atEnd(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))
}
