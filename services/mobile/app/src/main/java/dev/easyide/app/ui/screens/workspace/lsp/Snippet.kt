package dev.easyide.app.ui.screens.workspace.lsp

/**
 * A parsed snippet ready to insert: [text] with tab stops as offsets into it.
 *
 * @property stops in visiting order - `$1`, `$2`, ... then `$0` - each with every range that
 *   number occupies (the first is where the caret goes; later ones are mirrors, inserted as
 *   written and not linked while typing - basic tab stops, not linked editing).
 */
data class SnippetText(val text: String, val stops: List<TabStop>) {
    /** Where the caret ends: `$0`, or the end of the text when the snippet has none. */
    val finalOffset: Int get() = stops.lastOrNull { it.index == 0 }?.ranges?.first()?.first ?: text.length
}

/** One tab stop number and the ranges it occupies. */
data class TabStop(val index: Int, val ranges: List<OpenRange>)

/** A half-open `[first, end)` span of offsets. */
data class OpenRange(val first: Int, val end: Int) {
    val isEmpty: Boolean get() = first == end
}

/**
 * The LSP / TextMate snippet grammar (LSP 3.17 "Snippet Syntax"): `$1`, `${1}`,
 * `${1:placeholder}` (nesting allowed), `${1|a,b|}` choices (the first option is inserted),
 * variables `$NAME`, `${NAME}`, `${NAME:default}`, and `\` escapes. Transforms
 * (`${1/re/fmt/}`) are parsed and dropped: the stop keeps its plain value.
 *
 * Unknown variables insert their default, or their name as the spec asks. Multi-line bodies
 * are re-indented to [indent] after every newline, and `\t` becomes [tab], so an inserted
 * block lines up with the line it was accepted on.
 */
class SnippetParser(
    private val source: String,
    private val indent: String,
    private val tab: String,
    private val variable: (String) -> String?,
) {
    private var i = 0
    private val out = StringBuilder()
    private val ranges = LinkedHashMap<Int, MutableList<OpenRange>>()

    fun parse(): SnippetText {
        parseUntil(terminators = "")
        val numbered = ranges.filterKeys { it != 0 }.toSortedMap().map { (k, v) -> TabStop(k, v) }
        val zero = ranges[0]?.let { listOf(TabStop(0, it)) }.orEmpty()
        return SnippetText(out.toString(), numbered + zero)
    }

    /** Emits text until one of [terminators] (not consumed) or the end. */
    private fun parseUntil(terminators: String) {
        while (i < source.length) {
            val c = source[i]
            when {
                c in terminators -> return
                c == '\\' && i + 1 < source.length && source[i + 1] in ESCAPABLE -> {
                    emit(source[i + 1])
                    i += 2
                }
                c == '$' && dollar() -> Unit
                else -> {
                    emit(c)
                    i++
                }
            }
        }
    }

    /** Parses a `$...` construct at [i]; false (nothing consumed) when it is a literal `$`. */
    private fun dollar(): Boolean {
        val next = source.getOrNull(i + 1) ?: return false
        return when {
            next.isDigit() -> {
                i++
                val n = number()
                mark(n, out.length, out.length)
                true
            }
            next == '{' -> braced()
            isVarStart(next) -> {
                i++
                insertVariable(name())
                true
            }
            else -> false
        }
    }

    private fun braced(): Boolean {
        val start = i
        i += 2
        val c = source.getOrNull(i)
        return when {
            c != null && c.isDigit() -> {
                bracedTabStop(number())
                true
            }
            c != null && isVarStart(c) -> {
                bracedVariable(name())
                true
            }
            else -> {
                i = start
                false
            }
        }
    }

    private fun bracedTabStop(n: Int) {
        val begin = out.length
        when (source.getOrNull(i)) {
            '}' -> i++
            ':' -> {
                i++
                parseUntil("}")
                i++
            }
            '|' -> {
                i++
                emitText(choices().firstOrNull().orEmpty())
            }
            '/' -> skipTransform()
            else -> Unit
        }
        mark(n, begin, out.length)
    }

    private fun bracedVariable(name: String) {
        when (source.getOrNull(i)) {
            '}' -> {
                i++
                insertVariable(name)
            }
            ':' -> {
                i++
                val known = variable(name)
                if (known != null) {
                    // The default is parsed (and discarded) so nested constructs are consumed.
                    val mark = out.length
                    parseUntil("}")
                    out.setLength(mark)
                    emitText(known)
                } else {
                    parseUntil("}")
                }
                i++
            }
            '/' -> {
                skipTransform()
                insertVariable(name)
            }
            else -> insertVariable(name)
        }
    }

    /** `${1|one,two|}`: the options, with `\,` `\|` `\\` escapes; consumes through `|}`. */
    private fun choices(): List<String> {
        val options = ArrayList<String>()
        val cur = StringBuilder()
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length -> {
                    cur.append(source[i + 1])
                    i += 2
                }
                c == ',' -> {
                    options += cur.toString()
                    cur.setLength(0)
                    i++
                }
                c == '|' && source.getOrNull(i + 1) == '}' -> {
                    options += cur.toString()
                    i += 2
                    return options
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        options += cur.toString()
        return options
    }

    /**
     * `/regex/format/options}`: three `/`-separated parts, escapes honoured, then `}`. The
     * format may nest `${1:/upcase}` constructs, whose braces must not end the transform.
     */
    private fun skipTransform() {
        var slashes = 0
        var depth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i += 2
                c == '$' && source.getOrNull(i + 1) == '{' -> {
                    depth++
                    i += 2
                }
                c == '}' && depth > 0 -> {
                    depth--
                    i++
                }
                c == '/' && depth == 0 -> {
                    slashes++
                    i++
                }
                c == '}' && slashes >= TRANSFORM_SLASHES -> {
                    i++
                    return
                }
                else -> i++
            }
        }
    }

    /** A known variable's value, else its name (LSP: an unknown variable inserts its name). */
    private fun insertVariable(name: String) = emitText(variable(name) ?: name)

    private fun mark(n: Int, start: Int, end: Int) {
        ranges.getOrPut(n) { ArrayList() } += OpenRange(start, end)
    }

    private fun number(): Int {
        val begin = i
        while (i < source.length && source[i].isDigit()) i++
        return source.substring(begin, i).toIntOrNull() ?: 0
    }

    private fun name(): String {
        val begin = i
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
        return source.substring(begin, i)
    }

    private fun isVarStart(c: Char) = c.isLetter() || c == '_'

    private fun emitText(s: String) = s.forEach(::emit)

    private fun emit(c: Char) {
        when (c) {
            '\n' -> out.append('\n').append(indent)
            '\t' -> out.append(tab)
            else -> out.append(c)
        }
    }

    companion object {
        /** Text escapes of the grammar; `,` and `|` are escapes only inside a choice. */
        private const val ESCAPABLE = "\$}\\"
        private const val TRANSFORM_SLASHES = 3

        fun parse(source: String, indent: String = "", tab: String = "\t", variable: (String) -> String? = { null }): SnippetText =
            SnippetParser(source, indent, tab, variable).parse()
    }
}

/**
 * The tab stops of an inserted snippet while the user fills them in: absolute document
 * offsets, moved along with every edit so they stay on their fields.
 */
data class SnippetSession(val stops: List<TabStop>, val current: Int) {

    val active: OpenRange? get() = stops.getOrNull(current)?.ranges?.firstOrNull()

    val hasNext: Boolean get() = current < stops.lastIndex

    val hasPrevious: Boolean get() = current > 0

    fun next(): SnippetSession = copy(current = (current + 1).coerceAtMost(stops.lastIndex))

    fun previous(): SnippetSession = copy(current = (current - 1).coerceAtLeast(0))

    /**
     * This session after the single replacement `[start, oldEnd) -> [start, newEnd)`, or null
     * once the edit falls outside every field (the user moved on). An edit inside or touching
     * a field grows or shrinks it; ranges after the edit shift.
     */
    fun shifted(start: Int, oldEnd: Int, newEnd: Int): SnippetSession? {
        fun contains(r: OpenRange) = start >= r.first && oldEnd <= r.end
        if (stops.none { s -> s.ranges.any(::contains) }) return null
        val delta = newEnd - oldEnd
        // The field being typed in grows; a neighbour that starts where it ends only moves.
        fun move(r: OpenRange, active: Boolean): OpenRange = when {
            active && contains(r) -> OpenRange(r.first, r.end + delta)
            r.first >= oldEnd -> OpenRange(r.first + delta, r.end + delta)
            r.end <= start -> r
            else -> OpenRange(minOf(r.first, start), maxOf(start, r.end + delta))
        }
        return copy(stops = stops.mapIndexed { n, s -> s.copy(ranges = s.ranges.map { move(it, n == current) }) })
    }

    companion object {
        /** A session for [snippet] inserted at [at]; null when it has no stops worth visiting. */
        fun start(snippet: SnippetText, at: Int): SnippetSession? {
            val stops = snippet.stops.map { s -> s.copy(ranges = s.ranges.map { OpenRange(it.first + at, it.end + at) }) }
            val onlyFinal = stops.all { it.index == 0 }
            return if (stops.isEmpty() || onlyFinal) null else SnippetSession(stops, 0)
        }
    }
}
