package dev.easyide.app.extensions.adapters

/** Snippet text after expansion; [selectionStart]..[selectionEnd] is where the caret lands (a range selects a placeholder). */
data class ExpandedSnippet(val text: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * Expands a TextMate/VS Code snippet body (sdk-reference `snippets`): `$1`, `${1:default}`
 * (nested), `${1|a,b|}` (first choice), `$0`, `$VAR` / `${VAR:default}` and `\` escapes of
 * `$`, `}` and `\`. Tab-stop navigation (snippet mode) comes with the completion UI; until
 * then the caret goes to the first tab stop, selecting its placeholder so typing replaces
 * it, else to `$0`, else to the end.
 *
 * [variables] resolves `TM_*`-style names; an unknown variable inserts its default, or
 * its own name as VS Code does. Multi-line bodies get [indent] after every newline, so a
 * snippet inserted in an indented block stays aligned. Malformed syntax degrades to
 * literal text instead of failing: snippets are author content, not code.
 */
object SnippetBody {

    fun expand(body: String, indent: String = "", variables: (String) -> String? = { null }): ExpandedSnippet {
        val p = Parser(body, variables)
        p.parseUntil(null)
        val text = p.out.toString()
        val stops = p.stops
        val first = stops.filterKeys { it > 0 }.minByOrNull { it.key }?.value
        val (start, end) = first ?: stops[0] ?: (text.length to text.length)
        return withIndent(ExpandedSnippet(text, start, end), indent)
    }

    private fun withIndent(s: ExpandedSnippet, indent: String): ExpandedSnippet {
        if (indent.isEmpty() || !s.text.contains('\n')) return s
        val out = StringBuilder()
        var start = s.selectionStart
        var end = s.selectionEnd
        s.text.forEachIndexed { i, c ->
            out.append(c)
            if (c == '\n') {
                out.append(indent)
                if (i < s.selectionStart) start += indent.length
                if (i < s.selectionEnd) end += indent.length
            }
        }
        return ExpandedSnippet(out.toString(), start, end)
    }

    private class Parser(val src: String, val variables: (String) -> String?) {
        var i = 0
        val out = StringBuilder()

        /** Tab stop number -> (start, end) of its first occurrence in [out]. */
        val stops = LinkedHashMap<Int, Pair<Int, Int>>()

        /** Appends until [close] (unconsumed at depth 0) or the end. */
        fun parseUntil(close: Char?) {
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\\' && i + 1 < src.length && src[i + 1] in ESCAPABLE -> { out.append(src[i + 1]); i += 2 }
                    close != null && c == close -> return
                    c == '$' -> dollar()
                    else -> { out.append(c); i++ }
                }
            }
        }

        private fun dollar() {
            val next = src.getOrNull(i + 1)
            when {
                next != null && next.isDigit() -> {
                    val (n, after) = number(i + 1)
                    i = after
                    mark(n, out.length, out.length)
                }
                next != null && isVarStart(next) -> {
                    val (name, after) = name(i + 1)
                    i = after
                    out.append(variables(name) ?: name)
                }
                next == '{' -> braced()
                else -> { out.append('$'); i++ }
            }
        }

        private fun braced() {
            val start = i
            i += 2
            val c = src.getOrNull(i)
            when {
                c != null && c.isDigit() -> {
                    val (n, after) = number(i)
                    i = after
                    when (src.getOrNull(i)) {
                        '}' -> { i++; mark(n, out.length, out.length) }
                        ':' -> {
                            i++
                            val from = out.length
                            parseUntil('}')
                            if (i < src.length) i++
                            mark(n, from, out.length)
                        }
                        '|' -> choice(n, start)
                        else -> literal(start)
                    }
                }
                c != null && isVarStart(c) -> {
                    val (name, after) = name(i)
                    i = after
                    when (src.getOrNull(i)) {
                        '}' -> { i++; out.append(variables(name) ?: name) }
                        ':' -> {
                            i++
                            val value = variables(name)
                            val from = out.length
                            parseUntil('}')
                            if (i < src.length) i++
                            // A resolved variable replaces its default.
                            if (value != null) { out.setLength(from); out.append(value) }
                        }
                        else -> literal(start)
                    }
                }
                else -> literal(start)
            }
        }

        /** `${n|a,b|}`: the first option is inserted. */
        private fun choice(n: Int, start: Int) {
            val close = src.indexOf("|}", i + 1)
            if (close < 0) { literal(start); return }
            val first = src.substring(i + 1, close).split(',').first().replace("\\,", ",").replace("\\|", "|")
            val from = out.length
            out.append(first)
            mark(n, from, out.length)
            i = close + 2
        }

        /** Not snippet syntax after all: emit `${` literally and continue after it. */
        private fun literal(start: Int) {
            out.append("\${")
            i = start + 2
        }

        private fun mark(n: Int, from: Int, to: Int) { stops.putIfAbsent(n, from to to) }

        private fun number(from: Int): Pair<Int, Int> {
            var j = from
            while (j < src.length && src[j].isDigit()) j++
            return (src.substring(from, j).toIntOrNull() ?: 0) to j
        }

        private fun name(from: Int): Pair<String, Int> {
            var j = from
            while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
            return src.substring(from, j) to j
        }

        private fun isVarStart(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'
    }

    private val ESCAPABLE = setOf('$', '}', '\\')
}
