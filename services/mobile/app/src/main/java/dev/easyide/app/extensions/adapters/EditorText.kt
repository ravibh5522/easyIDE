package dev.easyide.app.extensions.adapters

/** Caret-relative text lookups for `${currentWord}`, `${lineText}` and snippet variables. Pure. */
object EditorText {

    /** The identifier-like word touching [offset] (VS Code's default word pattern, simplified to `[A-Za-z0-9_]`). */
    fun wordAt(text: String, offset: Int): String {
        val at = offset.coerceIn(0, text.length)
        var start = at
        while (start > 0 && isWord(text[start - 1])) start--
        var end = at
        while (end < text.length && isWord(text[end])) end++
        return text.substring(start, end)
    }

    fun lineAt(text: String, offset: Int): String {
        val at = offset.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', at - 1) + 1
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    /** The leading whitespace of the line holding [offset]: what a multi-line snippet continues with. */
    fun indentAt(text: String, offset: Int): String = lineAt(text, offset).takeWhile { it == ' ' || it == '\t' }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
}
