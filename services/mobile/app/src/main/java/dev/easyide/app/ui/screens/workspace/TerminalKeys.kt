package dev.easyide.app.ui.screens.workspace

import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import dev.easyide.app.extensions.adapters.KeyRows
import dev.easyide.app.extensions.adapters.SnippetBody
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.RowKey

/**
 * The terminal's accessory row, registered as the built-in key row
 * `builtin.terminal` (customization.md sec 10): navigation and control keys as
 * `Key` actions, symbols as `Insert`.
 *
 * A soft keyboard has no arrows, and buries `| ~ / $ > &` two layers deep -
 * which is most of what shell work is made of. This row puts them one tap
 * away. With a real pty ([TerminalPane]), `^C` is an actual `SIGINT` the
 * shell's own line discipline delivers, `↑`/`↓` are the VT100 sequences the
 * shell's own readline already recognises as history.
 *
 * Navigation first (fixed position, so muscle memory works), then the symbols
 * roughly by how often a shell needs them.
 */
object TerminalKeyboard {

    private val KEYS: List<RowKey> = listOf(
        key("^C", "ctrl+c"),
        key("↑", "up"),
        key("↓", "down"),
        key("←", "left"),
        key("→", "right"),
        // Ctrl-L: the readline binding for "clear screen" in every shell that
        // matters, so this behaves exactly as if the user typed it.
        key("clr", "ctrl+l"),
        key("Tab", "tab"),
        key("Esc", "escape"),
    ) + listOf("-", "_", "/", "~", ".", "|", "$", "*", "&", ">", "<", "\"", "'", "(", ")", "[", "]", "{", "}", "=", ";", "#", "!", "\\")
        .map { RowKey(it, KeyAction.Insert(it), null) }

    /** [title] is the row's display name (a string resource at the call site). */
    fun row(title: String) = KeyRowContribution(KeyRows.BUILTIN_TERMINAL, title, null, KEYS)

    private fun key(label: String, chord: String) = RowKey(label, KeyAction.Key(chord), null)
}

/**
 * Turns a key-row action into what a terminal session receives. `Insert` writes its
 * text, `Snippet` its body with placeholders stripped, `Key` the escape sequence the
 * terminal's own key encoder produces for that chord (so application cursor mode is
 * honoured). `Command` is not terminal input; the caller runs it.
 */
object TerminalKeyInput {

    fun bytesFor(action: KeyAction, session: TerminalSession): String? = when (action) {
        is KeyAction.Insert -> action.text
        is KeyAction.Snippet -> SnippetBody.expand(action.body).text
        is KeyAction.Key -> chordBytes(action.chord, session)
        is KeyAction.Command -> null
    }

    private fun chordBytes(text: String, session: TerminalSession): String? {
        val chord = KeyNames.parse(text) ?: return null
        val letter = chord.keyCode - android.view.KeyEvent.KEYCODE_A
        // Ctrl+letter is a control byte the encoder leaves to the caller (TerminalView does the same).
        if (chord.ctrl && !chord.alt && letter in 0 until ALPHABET) return (letter + 1).toChar().toString()
        var mods = 0
        if (chord.ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (chord.alt) mods = mods or KeyHandler.KEYMOD_ALT
        if (chord.shift) mods = mods or KeyHandler.KEYMOD_SHIFT
        val emulator = session.emulator
        return KeyHandler.getCode(
            chord.keyCode, mods,
            emulator?.isCursorKeysApplicationMode == true, emulator?.isKeypadApplicationMode == true,
        )
    }

    private const val ALPHABET = 26
}
