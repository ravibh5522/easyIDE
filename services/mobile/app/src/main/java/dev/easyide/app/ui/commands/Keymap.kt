package dev.easyide.app.ui.commands

import android.view.KeyEvent
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import dev.easyide.extensions.whenclause.WhenExpr
import kotlinx.serialization.json.JsonElement

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
 * Where a binding applies: the one context key the app has until when-clauses
 * arrive, spelled as VS Code's `when` so keybindings.json entries carry over.
 * [OUTSIDE_TERMINAL] is for plain Ctrl+letter chords, which are shell control
 * bytes (Ctrl+S is XOFF, Ctrl+W deletes a word, Ctrl+B is readline back / the
 * tmux prefix) and must not be stolen from a focused terminal.
 */
enum class KeyFocus(val whenText: String?) {
    ANYWHERE(null),
    OUTSIDE_TERMINAL("!terminalFocus"),
    TERMINAL_ONLY("terminalFocus");

    fun matches(terminalFocused: Boolean): Boolean = when (this) {
        ANYWHERE -> true
        OUTSIDE_TERMINAL -> !terminalFocused
        TERMINAL_ONLY -> terminalFocused
    }

    /** False only when no focus state satisfies both, i.e. the two can never fire for the same key press. */
    fun overlaps(other: KeyFocus): Boolean = this == ANYWHERE || other == ANYWHERE || this == other

    companion object {
        /** A `when` text this app can evaluate, whitespace-insensitive; null when it cannot. */
        fun parse(whenText: String?): KeyFocus? {
            val normalized = whenText?.filterNot(Char::isWhitespace)
            return entries.firstOrNull { it.whenText == normalized?.ifEmpty { null } }
        }
    }
}

/**
 * [prefix] makes a two-step chord (VS Code's `ctrl+k ctrl+i`): [chord] only counts right after
 * [prefix] was pressed; see [ChordDispatcher]. [whenExpr] is a binding's full `when`
 * (extension layer), evaluated at dispatch against the context-key snapshot on top of
 * [focus]; [args] go to the command.
 */
data class KeyBinding(
    val chord: KeyChord,
    val command: String,
    val focus: KeyFocus,
    val prefix: KeyChord? = null,
    val whenExpr: WhenExpr? = null,
    val args: JsonElement? = null,
) {
    /** Whether this binding may fire in the given focus and context. A `when` with no context never holds. */
    fun applies(terminalFocused: Boolean, context: ContextLookup?): Boolean =
        focus.matches(terminalFocused) && (whenExpr == null || (context != null && WhenEvaluator.evaluate(whenExpr, context)))
}

/**
 * Layers, low to high (customization.md sec 7.1): the built-in table, contributed
 * keybindings in enabled-set order, then keybindings.json (see [KeymapResolver]).
 * Dispatch scans last to first, so a later layer wins, and the first candidate whose
 * focus and `when` hold fires.
 */
class Keymap(val bindings: List<KeyBinding>) {

    /**
     * The binding that fires for [chord] (completing [prefix] when one is pending) in this
     * focus [context], or null to let the key through.
     */
    fun bindingFor(chord: KeyChord, terminalFocused: Boolean, context: ContextLookup? = null, prefix: KeyChord? = null): KeyBinding? =
        bindings.lastOrNull { it.chord == chord && it.prefix == prefix && it.applies(terminalFocused, context) }

    fun commandFor(chord: KeyChord, terminalFocused: Boolean, context: ContextLookup? = null, prefix: KeyChord? = null): String? =
        bindingFor(chord, terminalFocused, context, prefix)?.command

    /** Whether [chord] starts a two-step chord whose binding could fire in this focus [context]. */
    fun isPrefix(chord: KeyChord, terminalFocused: Boolean, context: ContextLookup? = null): Boolean =
        bindings.any { it.prefix == chord && it.applies(terminalFocused, context) }

    /** The chord shown next to [command] in the palette: the one that would win dispatch. */
    fun chordFor(command: String): KeyChord? = bindings.lastOrNull { it.command == command }?.chord

    /** The palette label of [command]'s winning binding, both steps of a two-step chord included. */
    fun labelFor(command: String): String? = bindings.lastOrNull { it.command == command }?.let { b ->
        listOfNotNull(b.prefix, b.chord).joinToString(" ") { label(it) }
    }

    /** [layer] on top of this map: its bindings win ties. */
    operator fun plus(layer: List<KeyBinding>): Keymap = Keymap(bindings + layer)

    companion object {
        /** The built-in keymap: the single source for workspace shortcuts. */
        val DEFAULT = Keymap(
            listOf(
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_P), CommandIds.SHOW_COMMANDS, KeyFocus.ANYWHERE),
                KeyBinding(ctrl(KeyEvent.KEYCODE_S), CommandIds.SAVE, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_W), CommandIds.CLOSE_EDITOR, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_TAB), CommandIds.NEXT_EDITOR, KeyFocus.ANYWHERE),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_TAB), CommandIds.PREVIOUS_EDITOR, KeyFocus.ANYWHERE),
                KeyBinding(ctrl(KeyEvent.KEYCODE_B), CommandIds.TOGGLE_EXPLORER, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_GRAVE), CommandIds.TOGGLE_TERMINAL, KeyFocus.ANYWHERE),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_G), CommandIds.TOGGLE_SOURCE_CONTROL, KeyFocus.ANYWHERE),
                KeyBinding(ctrl(KeyEvent.KEYCODE_SPACE), CommandIds.TRIGGER_SUGGEST, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_SPACE), CommandIds.TRIGGER_PARAMETER_HINTS, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_I), CommandIds.SHOW_HOVER, KeyFocus.OUTSIDE_TERMINAL, prefix = ctrl(KeyEvent.KEYCODE_K)),
                KeyBinding(KeyChord(KeyEvent.KEYCODE_F12), CommandIds.REVEAL_DEFINITION, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_F12), CommandIds.GO_TO_IMPLEMENTATION, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(KeyChord(KeyEvent.KEYCODE_F12, shift = true), CommandIds.GO_TO_REFERENCES, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(KeyChord(KeyEvent.KEYCODE_F2), CommandIds.RENAME, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_PERIOD), CommandIds.QUICK_FIX, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(KeyChord(KeyEvent.KEYCODE_F, shift = true, alt = true), CommandIds.FORMAT_DOCUMENT, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_O), CommandIds.GOTO_SYMBOL, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrl(KeyEvent.KEYCODE_T), CommandIds.SHOW_ALL_SYMBOLS, KeyFocus.OUTSIDE_TERMINAL),
                KeyBinding(ctrlShift(KeyEvent.KEYCODE_M), CommandIds.SHOW_PROBLEMS, KeyFocus.ANYWHERE),
            )
        )

        /** Keys whose `KEYCODE_*` name is not what the keycap shows. */
        private val KEY_LABELS = mapOf(
            KeyEvent.KEYCODE_GRAVE to "`",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_PERIOD to ".",
        )

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

/**
 * Key dispatch with two-step chords: after a prefix chord (Ctrl+K) the next non-modifier key
 * completes or cancels it, and is consumed either way, as in VS Code. One per screen; the
 * pending prefix is UI state, kept out of the immutable [Keymap]. `when` clauses are checked
 * against the context at each step, so a prefix only arms when some completion could fire.
 */
class ChordDispatcher(private val keymap: Keymap) {
    private var pending: KeyChord? = null

    /** Down events only: acting on the matching key-up too would run every command twice. */
    fun dispatch(event: KeyEvent, terminalFocused: Boolean, registry: CommandRegistry, context: ContextLookup? = null): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(event.keyCode)) return false
        val chord = KeyChord.of(event)
        val prefix = pending
        if (prefix != null) {
            pending = null
            keymap.bindingFor(chord, terminalFocused, context, prefix)?.let { registry.execute(it.command, it.args) }
            return true
        }
        if (keymap.isPrefix(chord, terminalFocused, context)) {
            pending = chord
            return true
        }
        val binding = keymap.bindingFor(chord, terminalFocused, context) ?: return false
        return registry.execute(binding.command, binding.args)
    }
}
