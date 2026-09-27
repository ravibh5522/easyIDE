package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sticky Ctrl and Alt for the terminal's touch key row. A soft keyboard cannot hold a modifier
 * while another key is pressed, and terminal programs are driven by chords (Ctrl+R, Ctrl+X then a
 * letter, Alt+B). Tapping Ctrl or Alt arms it for the next key only, like Termux's extra keys.
 *
 * One terminal view has the keyboard at a time, so the state is process-wide rather than per session.
 */
object TerminalModifiers {

    const val CTRL_ID = "terminal.modifier.ctrl"
    const val ALT_ID = "terminal.modifier.alt"

    var ctrl by mutableStateOf(false)
        private set
    var alt by mutableStateOf(false)
        private set

    /** Handles a row key by command id; true when [id] was a modifier and is now toggled. */
    fun toggle(id: String): Boolean = when (id) {
        CTRL_ID -> { ctrl = !ctrl; true }
        ALT_ID -> { alt = !alt; true }
        else -> false
    }

    /** Disarms both once a key has used them. */
    fun consume() {
        ctrl = false
        alt = false
    }

    /**
     * [bytes] with the armed modifiers applied, then disarmed. Only a single character is
     * combined: an escape sequence (an arrow key) already carries its own modifiers.
     */
    fun wrap(bytes: String): String {
        val out = if (bytes.length == 1) {
            val key = if (ctrl) controlOf(bytes[0]) else bytes[0]
            if (alt) "$ESC$key" else key.toString()
        } else {
            bytes
        }
        consume()
        return out
    }

    /** The control byte for [c] as a terminal defines it (Ctrl+A is 1, Ctrl+[ is Esc), else [c] itself. */
    fun controlOf(c: Char): Char = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        ' ', '2' -> '\u0000'
        '[', '3' -> ESC
        '\\', '4' -> '\u001c'
        ']', '5' -> '\u001d'
        '^', '6' -> '\u001e'
        '_', '7', '/' -> '\u001f'
        '8' -> '\u007f'
        else -> c
    }

    private const val ESC = '\u001b'
}
