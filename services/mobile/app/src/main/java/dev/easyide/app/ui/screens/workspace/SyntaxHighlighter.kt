package dev.tabcode.app.ui.screens.workspace

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.tabcode.app.ui.theme.EditorColors

/**
 * Regex-based token colouring - comments, strings, numbers and keywords.
 *
 * Deliberately not a parser: a real one belongs to the language server the
 * Theia integration will bring. This exists so editing a file looks like code
 * rather than a notepad.
 *
 * Comments and strings are matched first and their ranges become "protected";
 * keyword and number matches falling inside them are skipped. That ordering is
 * what stops `for` inside a string from being coloured as a keyword, and it is
 * done with a sorted range list plus binary search rather than by removing
 * overlapping spans afterwards, which was quadratic on large files.
 */
object SyntaxHighlighter {

    private val COMMON_KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "import", "package", "return",
        "if", "else", "when", "for", "while", "do", "break", "continue", "in", "is", "as",
        "true", "false", "null", "private", "public", "internal", "override", "suspend",
        "def", "print", "None", "True", "False", "elif", "lambda", "with", "from", "and", "or", "not",
        "function", "const", "let", "export", "default", "async", "await", "new", "this", "typeof",
        "int", "float", "double", "void", "static", "final", "struct", "enum", "type",
    )

    private val COMMENT_REGEX = Regex("""(//[^\n]*)|(#[^\n]*)|(/\*[\s\S]*?\*/)""")
    private val STRING_REGEX = Regex(""""([^"\\\n]|\\.)*"|'([^'\\\n]|\\.)*'|`([^`\\]|\\.)*`""")
    private val NUMBER_REGEX = Regex("""\b\d+(\.\d+)?\b""")
    private val WORD_REGEX = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

    /** Extensions we do not colour, to avoid nonsense highlighting. */
    private val PLAIN_EXTENSIONS = setOf("md", "txt", "log", "csv", "json", "")

    fun highlight(source: String, fileName: String, colors: EditorColors): AnnotatedString {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in PLAIN_EXTENSIONS || source.isEmpty()) return AnnotatedString(source)

        val builder = AnnotatedString.Builder(source)

        // Pass 1: comments and strings win outright, and define protected ranges.
        val protectedStarts = ArrayList<Int>()
        val protectedEnds = ArrayList<Int>()

        fun protect(start: Int, end: Int, color: androidx.compose.ui.graphics.Color) {
            builder.addStyle(SpanStyle(color = color), start, end)
            protectedStarts += start
            protectedEnds += end
        }

        COMMENT_REGEX.findAll(source).forEach { protect(it.range.first, it.range.last + 1, colors.comment) }
        STRING_REGEX.findAll(source).forEach { match ->
            // A string inside an already-protected comment adds nothing.
            if (!isProtected(match.range.first, protectedStarts, protectedEnds)) {
                protect(match.range.first, match.range.last + 1, colors.string)
            }
        }

        // findAll yields in source order per pattern, but the two patterns were
        // interleaved, so sort once before the binary searches below.
        val order = protectedStarts.indices.sortedBy { protectedStarts[it] }
        val sortedStarts = IntArray(order.size) { protectedStarts[order[it]] }
        val sortedEnds = IntArray(order.size) { protectedEnds[order[it]] }

        // Pass 2: keywords and numbers, only where nothing already claimed them.
        WORD_REGEX.findAll(source).forEach { match ->
            if (match.value in COMMON_KEYWORDS && !isProtected(match.range.first, sortedStarts, sortedEnds)) {
                builder.addStyle(SpanStyle(color = colors.keyword), match.range.first, match.range.last + 1)
            }
        }
        NUMBER_REGEX.findAll(source).forEach { match ->
            if (!isProtected(match.range.first, sortedStarts, sortedEnds)) {
                builder.addStyle(SpanStyle(color = colors.number), match.range.first, match.range.last + 1)
            }
        }

        return builder.toAnnotatedString()
    }

    private fun isProtected(offset: Int, starts: List<Int>, ends: List<Int>): Boolean =
        starts.indices.any { offset >= starts[it] && offset < ends[it] }

    /** Binary search over the sorted ranges built in pass 1. */
    private fun isProtected(offset: Int, starts: IntArray, ends: IntArray): Boolean {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                offset < starts[mid] -> high = mid - 1
                offset >= ends[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}
