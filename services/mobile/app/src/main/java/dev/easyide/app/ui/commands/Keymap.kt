package dev.easyide.app.ui.commands

import android.view.KeyEvent

/** A single key press with its modifiers, compared exactly (Ctrl+S never matches Ctrl+Shift+S). */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    companion object {
        fun of(event: KeyEvent) = KeyChord(
            keyCode = event.keyCode,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        )
    }
}

/**
 * [inTerminal] false means the chord goes to the shell when a terminal has
 * focus - the stand-in for VS Code's `when: "!terminalFocus"` until when-clauses
 * exist. Plain Ctrl+letter chords are shell control bytes (Ctrl+S is XOFF,
 * Ctrl+W deletes a word, Ctrl+B is readline back / the tmux prefix), so they
 * must not be stolen there.
 */
data class KeyBinding(val chord: KeyChord, val command: String, val inTerminal: Boolean)

class Keymap(private val bindings: List<KeyBinding>) {

    /**
     * The command bound to [chord] in this focus context, or null to let the
     * key through. Scanned last to first so a later entry (a user override,
     * once keybindings.json exists) wins over the default table.
     */
    fun commandFor(chord: KeyChord, terminalFocused: Boolean): String? =
        bindings.lastOrNull { it.chord == chord && (it.inTerminal || !terminalFocused) }?.command

    /** The chord shown next to [command] in the palette: the one that would win dispatch. */
    fun chordFor(command: String): KeyChord? = bindings.lastOrNull { it.command == command }?.chord

    /** Down events only: acting on the matching key-up too would run every command twice. */
    fun dispatch(event: KeyEvent, terminalFocused: Boolean, registry: CommandRegistry): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val id = commandFor(KeyChord.of(event), terminalFocused) ?: return false
        return registry.execute(id)
    }

    companion object {
        /** The built-in keymap: the single source for workspace shortcuts. */
        val DEFAULT = Keymap(
            listOf(
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_P), CommandIds.SHOW_COMMANDS, inTerminal = true),
                KeyBinding(ctrl(KeyEvent.KEYCODE_S), CommandIds.SAVE, inTerminal = false),
                KeyBinding(ctrl(KeyEvent.KEYCODE_W), CommandIds.CLOSE_EDITOR, inTerminal = false),
                KeyBinding(ctrl(KeyEvent.KEYCODE_TAB), CommandIds.NEXT_EDITOR, inTerminal = true),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_TAB), CommandIds.PREVIOUS_EDITOR, inTerminal = true),
                KeyBinding(ctrl(KeyEvent.KEYCODE_B), CommandIds.TOGGLE_EXPLORER, inTerminal = false),
                KeyBinding(ctrl(KeyEvent.KEYCODE_GRAVE), CommandIds.TOGGLE_TERMINAL, inTerminal = true),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_G), CommandIds.TOGGLE_SOURCE_CONTROL, inTerminal = true),
            )
        )

        /** Keys whose `KEYCODE_*` name is not what the keycap shows. */
        private val KEY_LABELS = mapOf(KeyEvent.KEYCODE_GRAVE to "`", KeyEvent.KEYCODE_TAB to "Tab")

        fun label(chord: KeyChord): String = buildList {
            if (chord.ctrl) add("Ctrl")
            if (chord.shift) add("Shift")
            if (chord.alt) add("Alt")
            if (chord.meta) add("Meta")
            add(KEY_LABELS[chord.keyCode] ?: KeyEvent.keyCodeToString(chord.keyCode).removePrefix("KEYCODE_"))
        }.joinToString("+")

        private fun ctrl(keyCode: Int) = KeyChord(keyCode, ctrl = true)
        private fun ctrlShift(keyCode: Int) = KeyChord(keyCode, ctrl = true, shift = true)
    }
}
