package dev.easyide.app.extensions.adapters

import android.view.KeyEvent

/** Buffer text plus selection after an editor key-row key. */
data class EditorEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * What a key-row `key` action does in the editor (customization.md sec 10: "send a key
 * event"): the editing and caret keys a soft keyboard lacks. Pure, over the buffer and
 * selection. Returns null for a chord that is not an editing key; the caller then treats
 * it as a shortcut (keymap dispatch).
 */
object EditorKeys {

    fun apply(text: String, start: Int, end: Int, keyCode: Int): EditorEdit? {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> caret(text, if (a != b) a else (a - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> caret(text, if (a != b) b else (b + 1).coerceAtMost(text.length))
            KeyEvent.KEYCODE_DPAD_UP -> caret(text, vertical(text, a, -1))
            KeyEvent.KEYCODE_DPAD_DOWN -> caret(text, vertical(text, b, 1))
            KeyEvent.KEYCODE_MOVE_HOME -> caret(text, text.lastIndexOf('\n', a - 1) + 1)
            KeyEvent.KEYCODE_MOVE_END -> caret(text, text.indexOf('\n', b).let { if (it < 0) text.length else it })
            KeyEvent.KEYCODE_DEL -> if (a != b) replace(text, a, b, "") else if (a == 0) EditorEdit(text, 0, 0) else replace(text, a - 1, a, "")
            KeyEvent.KEYCODE_FORWARD_DEL -> if (a != b) replace(text, a, b, "") else if (a == text.length) EditorEdit(text, a, a) else replace(text, a, a + 1, "")
            KeyEvent.KEYCODE_ENTER -> replace(text, a, b, "\n" + EditorText.indentAt(text, a))
            KeyEvent.KEYCODE_TAB -> replace(text, a, b, TAB)
            else -> null
        }
    }

    private fun caret(text: String, at: Int) = EditorEdit(text, at, at)

    private fun replace(text: String, from: Int, to: Int, with: String): EditorEdit {
        val next = text.substring(0, from) + with + text.substring(to)
        return EditorEdit(next, from + with.length, from + with.length)
    }

    /** Same column on the previous/next line, clamped to that line's length. */
    private fun vertical(text: String, at: Int, direction: Int): Int {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val column = at - lineStart
        return if (direction < 0) {
            if (lineStart == 0) 0 else {
                val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
                minOf(prevStart + column, lineStart - 1)
            }
        } else {
            val lineEnd = text.indexOf('\n', at)
            if (lineEnd < 0) text.length else {
                val nextEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
                minOf(lineEnd + 1 + column, nextEnd)
            }
        }
    }

    private const val TAB = "\t"
}
