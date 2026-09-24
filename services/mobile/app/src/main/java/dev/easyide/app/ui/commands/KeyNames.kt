package dev.easyide.app.ui.commands

import android.view.KeyEvent

/**
 * The single table between VS Code key names (`ctrl+shift+p`, `ctrl+\``) and
 * Android key codes (docs/ui-shell/arch.md keybinding-table rule), so
 * keybindings.json written for VS Code means the same keys here. Modifiers
 * are normalised to `ctrl+shift+alt+meta` order; `cmd` is Meta (sdk-reference).
 */
object KeyNames {

    private val KEYS: Map<String, Int> = buildMap {
        ('a'..'z').forEachIndexed { i, c -> put(c.toString(), KeyEvent.KEYCODE_A + i) }
        ('0'..'9').forEachIndexed { i, c -> put(c.toString(), KeyEvent.KEYCODE_0 + i) }
        (1..12).forEach { put("f$it", KeyEvent.KEYCODE_F1 + it - 1) }
        put("tab", KeyEvent.KEYCODE_TAB)
        put("enter", KeyEvent.KEYCODE_ENTER)
        put("escape", KeyEvent.KEYCODE_ESCAPE)
        put("space", KeyEvent.KEYCODE_SPACE)
        put("backspace", KeyEvent.KEYCODE_DEL)
        put("delete", KeyEvent.KEYCODE_FORWARD_DEL)
        put("insert", KeyEvent.KEYCODE_INSERT)
        put("home", KeyEvent.KEYCODE_MOVE_HOME)
        put("end", KeyEvent.KEYCODE_MOVE_END)
        put("pageup", KeyEvent.KEYCODE_PAGE_UP)
        put("pagedown", KeyEvent.KEYCODE_PAGE_DOWN)
        put("up", KeyEvent.KEYCODE_DPAD_UP)
        put("down", KeyEvent.KEYCODE_DPAD_DOWN)
        put("left", KeyEvent.KEYCODE_DPAD_LEFT)
        put("right", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("`", KeyEvent.KEYCODE_GRAVE)
        put("-", KeyEvent.KEYCODE_MINUS)
        put("=", KeyEvent.KEYCODE_EQUALS)
        put("[", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("]", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("\\", KeyEvent.KEYCODE_BACKSLASH)
        put(";", KeyEvent.KEYCODE_SEMICOLON)
        put("'", KeyEvent.KEYCODE_APOSTROPHE)
        put(",", KeyEvent.KEYCODE_COMMA)
        put(".", KeyEvent.KEYCODE_PERIOD)
        put("/", KeyEvent.KEYCODE_SLASH)
    }

    private val NAMES: Map<Int, String> = KEYS.entries.associate { (name, code) -> code to name }

    private const val CTRL = "ctrl"
    private const val SHIFT = "shift"
    private const val ALT = "alt"
    private const val META = "meta"
    private const val CMD = "cmd"

    /**
     * One key press, e.g. `ctrl+shift+p`. Null for unknown names, repeated or
     * missing keys, and two-press chords (`ctrl+k ctrl+s`), which the dispatcher
     * does not support yet - the JSON editor reports those instead of dropping them silently.
     */
    fun parse(text: String): KeyChord? {
        val s = text.trim().lowercase()
        if (s.isEmpty() || s.any(Char::isWhitespace)) return null
        val parts = s.split('+')
        if (parts.any(String::isEmpty)) return null
        val mods = parts.dropLast(1)
        if (mods.toSet().size != mods.size) return null
        if (mods.any { it !in setOf(CTRL, SHIFT, ALT, META, CMD) }) return null
        val code = KEYS[parts.last()] ?: return null
        return KeyChord(
            keyCode = code,
            ctrl = CTRL in mods,
            shift = SHIFT in mods,
            alt = ALT in mods,
            meta = META in mods || CMD in mods,
        )
    }

    /** VS Code spelling of [chord]; the inverse of [parse] for every chord it accepts. */
    fun format(chord: KeyChord): String = buildList {
        if (chord.ctrl) add(CTRL)
        if (chord.shift) add(SHIFT)
        if (chord.alt) add(ALT)
        if (chord.meta) add(META)
        add(NAMES[chord.keyCode] ?: chord.keyCode.toString())
    }.joinToString("+")
}
