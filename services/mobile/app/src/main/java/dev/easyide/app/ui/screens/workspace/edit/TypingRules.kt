package dev.easyide.app.ui.screens.workspace.edit

/**
 * A buffer and its selection, independent of any UI toolkit, so edit rules can
 * be driven from the text field, the terminal-style key row or a command, and
 * be tested on the JVM. Offsets may be given in either order.
 */
data class TextState(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart) {
    val min: Int get() = minOf(selectionStart, selectionEnd)
    val max: Int get() = maxOf(selectionStart, selectionEnd)
    val collapsed: Boolean get() = selectionStart == selectionEnd
}

/**
 * Behaviour switches for [TypingRules]. The defaults stand in for settings that
 * are not wired yet; each field names the key it will be read from
 * (docs/extension-sdk/sdk-reference.md, editor settings table).
 */
data class TypingOptions(
    /** `editor.autoClosingBrackets` (`languageDefined`). */
    val autoClose: Boolean = true,
    /** `editor.autoSurround` (`languageDefined`). */
    val autoSurround: Boolean = true,
    /** `editor.autoIndent` (`full`). */
    val autoIndent: Boolean = true,
    /** `editor.tabSize` x spaces with `editor.insertSpaces`; a tab-indented line keeps tabs. */
    val indentUnit: String = "    ",
)

/**
 * Language-aware reactions to typing: auto-close and overtype of pairs,
 * surround-with-pair, deleting an empty pair, and indentation on Enter and on
 * a closing bracket. Driven by a [LanguageConfig].
 *
 * [onChange] receives the state before and after a text-field edit and returns
 * what the field should show; anything that is not a single typed character or
 * a single backspace (paste, IME recomposition, undo) passes through untouched.
 */
object TypingRules {

    fun onChange(old: TextState, new: TextState, config: LanguageConfig, options: TypingOptions): TextState {
        insertedChar(old, new)?.let { c -> return onType(old, c, config, options) ?: new }
        if (options.autoClose && isBackspace(old, new)) deleteEmptyPair(old, config)?.let { return it }
        return new
    }

    /** What typing [c] over [state]'s selection produces, or null for the plain insertion. */
    fun onType(state: TextState, c: Char, config: LanguageConfig, options: TypingOptions): TextState? {
        if (c == '\n') {
            if (!options.autoIndent) return null
            val text = state.text.substring(0, state.min) + state.text.substring(state.max)
            return enter(text, state.min, config, options)
        }
        if (!state.collapsed) return if (options.autoSurround) surround(state, c, config) else null
        overtype(state, c, config)?.let { return it }
        if (options.autoClose) autoClose(state, c, config)?.let { return it }
        if (options.autoIndent) return outdentOnClose(state, c, config, options)
        return null
    }

    /** The one character that replaced [old]'s selection to make [new], if that is all that happened. */
    private fun insertedChar(old: TextState, new: TextState): Char? {
        val start = old.min
        val end = old.max
        if (new.text.length != old.text.length - (end - start) + 1) return null
        if (new.selectionStart != start + 1 || new.selectionEnd != start + 1) return null
        if (!new.text.regionMatches(0, old.text, 0, start)) return null
        if (!new.text.regionMatches(start + 1, old.text, end, old.text.length - end)) return null
        return new.text[start]
    }

    private fun isBackspace(old: TextState, new: TextState): Boolean {
        val p = old.min
        return old.collapsed && p > 0 && new.collapsed && new.min == p - 1 &&
            new.text.length == old.text.length - 1 &&
            new.text.regionMatches(0, old.text, 0, p - 1) &&
            new.text.regionMatches(p - 1, old.text, p, old.text.length - p)
    }

    private fun surround(state: TextState, c: Char, config: LanguageConfig): TextState? {
        val pair = config.surroundingPairs.firstOrNull { it.open.length == 1 && it.open[0] == c && it.close.isNotEmpty() }
            ?: return null
        val text = state.text
        val out = text.substring(0, state.min) + pair.open + text.substring(state.min, state.max) +
            pair.close + text.substring(state.max)
        return TextState(out, state.min + 1, state.max + 1)
    }

    /** Typing a closer that is already next to the caret steps over it instead of doubling it. */
    private fun overtype(state: TextState, c: Char, config: LanguageConfig): TextState? {
        val p = state.min
        if (p >= state.text.length || state.text[p] != c) return null
        val pair = config.autoClosingPairs.firstOrNull { it.close.length == 1 && it.close[0] == c } ?: return null
        // A quote next to the caret is only a closer when the caret is inside that string.
        if (pair.open == pair.close) {
            val lineStart = state.text.lastIndexOf('\n', p - 1) + 1
            if (lineScope(state.text.substring(lineStart, p), config) != SCOPE_STRING) return null
        }
        return TextState(state.text, p + 1)
    }

    private fun autoClose(state: TextState, c: Char, config: LanguageConfig): TextState? {
        val text = state.text
        val p = state.min
        val lineStart = text.lastIndexOf('\n', p - 1) + 1
        val typed = text.substring(lineStart, p) + c
        // Longest match wins so Python's `f"` beats a bare `"`.
        val pair = config.autoClosingPairs
            .filter { it.open.last() == c && typed.endsWith(it.open) }
            .maxByOrNull { it.open.length } ?: return null

        val next = text.getOrNull(p)
        if (next != null && next != '\n' && next !in config.autoCloseBefore) return null
        val openStart = typed.length - pair.open.length
        // `don't` must not become `don't'`.
        if (pair.open == pair.close && openStart > 0 && typed[openStart - 1].isLetterOrDigit()) return null
        if (pair.notIn.isNotEmpty() && lineScope(typed.substring(0, openStart), config) in pair.notIn) return null

        return TextState(text.substring(0, p) + c + pair.close + text.substring(p), p + 1)
    }

    /** Backspace between an auto-closed pair removes both halves. */
    private fun deleteEmptyPair(old: TextState, config: LanguageConfig): TextState? {
        val p = old.min
        val text = old.text
        if (p >= text.length) return null
        val pair = config.autoClosingPairs.firstOrNull {
            it.open.length == 1 && it.close.length == 1 && it.open[0] == text[p - 1] && it.close[0] == text[p]
        } ?: return null
        return TextState(text.substring(0, p - 1) + text.substring(p + pair.close.length), p - 1)
    }

    /** A closing bracket typed on a whitespace-only line moves that line out one level. */
    private fun outdentOnClose(state: TextState, c: Char, config: LanguageConfig, options: TypingOptions): TextState? {
        if (config.brackets.none { it.close.length == 1 && it.close[0] == c }) return null
        val text = state.text
        val p = state.min
        val lineStart = text.lastIndexOf('\n', p - 1) + 1
        val indent = text.substring(lineStart, p)
        if (indent.isEmpty() || indent.isNotBlank()) return null
        val reduced = outdent(indent, unitFor(indent, options))
        return TextState(text.substring(0, lineStart) + reduced + c + text.substring(p), lineStart + reduced.length + 1)
    }

    /**
     * Enter at [caret]: keeps the line's indentation, then applies the first
     * matching `onEnterRules` entry, else bracket and `indentationRules` logic.
     */
    fun enter(text: String, caret: Int, config: LanguageConfig, options: TypingOptions): TextState {
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, caret)
        val after = text.substring(caret, lineEnd)
        val previous = if (lineStart == 0) "" else text.substring(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)

        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val unit = unitFor(indent, options)
        val rule = config.onEnterRules.firstOrNull {
            it.beforeText.containsMatchIn(before) &&
                (it.afterText?.containsMatchIn(after) ?: true) &&
                (it.previousLineText?.containsMatchIn(previous) ?: true)
        }
        val action = rule?.indent ?: defaultAction(before, after, config)
        val append = rule?.appendText.orEmpty().replace("\t", unit)

        val inner = when (action) {
            IndentAction.NONE -> indent + append
            IndentAction.INDENT -> indent + unit + append
            IndentAction.OUTDENT -> outdent(indent, unit) + append
            // VS Code's reading: the configured text *is* the inner line's
            // extra (`"\t"` for braces, `" * "` for doc comments); one level
            // only when a rule or the bracket fallback gives none.
            IndentAction.INDENT_OUTDENT -> indent + append.ifEmpty { unit }
        }.let { it.dropLast(minOf(rule?.removeText ?: 0, it.length)) }
        val tail = if (action == IndentAction.INDENT_OUTDENT) "\n" + indent else ""
        val insert = "\n" + inner
        return TextState(text.substring(0, caret) + insert + tail + text.substring(caret), caret + insert.length)
    }

    private fun defaultAction(before: String, after: String, config: LanguageConfig): IndentAction {
        val trimmed = before.trimEnd()
        val open = config.brackets.firstOrNull { trimmed.endsWith(it.open) }
        return when {
            open != null && after.startsWith(open.close) -> IndentAction.INDENT_OUTDENT
            open != null -> IndentAction.INDENT
            config.increaseIndent?.containsMatchIn(before) == true -> IndentAction.INDENT
            else -> IndentAction.NONE
        }
    }

    private fun unitFor(indent: String, options: TypingOptions): String =
        if (indent.startsWith('\t')) "\t" else options.indentUnit

    internal fun outdent(indent: String, unit: String): String = when {
        indent.endsWith(unit) -> indent.dropLast(unit.length)
        indent.endsWith('\t') -> indent.dropLast(1)
        else -> indent.dropLast(minOf(indent.takeLastWhile { it == ' ' }.length, unit.length))
    }

    /**
     * Whether the end of [linePrefix] sits in a string or line comment, judged
     * from that line alone. Multi-line strings and block comments are not
     * tracked; that is what keeps this cheap enough to run per keystroke.
     */
    internal fun lineScope(linePrefix: String, config: LanguageConfig): String? {
        val quotes = config.autoClosingPairs.filter { it.open.length == 1 && it.open == it.close }.map { it.open[0] }
        val comment = config.lineComment
        var quote: Char? = null
        var i = 0
        while (i < linePrefix.length) {
            val c = linePrefix[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote != null -> Unit
                c in quotes -> quote = c
                comment != null && linePrefix.startsWith(comment, i) -> return SCOPE_COMMENT
            }
            i++
        }
        return if (quote != null) SCOPE_STRING else null
    }
}
