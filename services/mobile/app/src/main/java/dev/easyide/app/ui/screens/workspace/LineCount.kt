package dev.easyide.app.ui.screens.workspace

/**
 * Line count of a buffer, cached against the buffer's identity.
 *
 * The editor gutter and the status bar both need it for the active tab, and
 * each used to count newlines on every recomposition - an O(n) scan of the
 * whole file on every keystroke, twice. An unchanged buffer is the same String
 * instance, so remembering the last one answers every repeat read for free and
 * the count runs once per edit. Only read from composition (the main thread).
 */
internal object LineCount {
    private var source: String? = null
    private var count = 1

    fun of(text: String): Int {
        if (text !== source) {
            count = text.count { it == '\n' } + 1
            source = text
        }
        return count
    }
}
