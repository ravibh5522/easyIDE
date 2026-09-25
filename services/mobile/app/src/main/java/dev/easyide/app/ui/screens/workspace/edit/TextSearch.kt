package dev.easyide.app.ui.screens.workspace.edit

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** What to look for. [text] is a literal unless [regex]. */
data class SearchQuery(
    val text: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** One match, a half-open UTF-16 range of the searched text. Never empty. */
data class Match(val start: Int, val end: Int)

sealed interface SearchResult {
    /** [truncated]: more than [TextSearch.MAX_MATCHES] matched and the rest were not collected. */
    data class Found(val matches: List<Match>, val truncated: Boolean) : SearchResult

    /** The regex did not compile; [message] is the engine's description, for the bar to show. */
    data class InvalidPattern(val message: String) : SearchResult

    /** The pattern ran past [TextSearch.TIMEOUT_MS] (catastrophic backtracking) and was stopped. */
    data object TimedOut : SearchResult
}

sealed interface ReplaceResult {
    data class Replaced(val text: String, val count: Int) : ReplaceResult
    data class Failed(val reason: SearchResult) : ReplaceResult
}

/**
 * Find and replace over a string. Pure and engine-neutral: the find bar drives it now, and the
 * virtualised editor's find (and project search) can reuse it unchanged.
 *
 * A user regex can backtrack exponentially (`(a+)+$`), and the whole editable buffer is at most
 * 256 KB, so every search runs against a [DeadlineChars] that aborts the engine once
 * [TIMEOUT_MS] has passed instead of hanging the thread. Empty matches (`^`, `a*`) are
 * skipped: there is nothing to highlight or replace, and skipping keeps "replace all" from
 * inserting at every position.
 */
object TextSearch {

    const val MAX_MATCHES = 10_000
    const val TIMEOUT_MS = 500L

    fun find(text: CharSequence, query: SearchQuery, timeoutMs: Long = TIMEOUT_MS): SearchResult {
        if (query.text.isEmpty()) return SearchResult.Found(emptyList(), truncated = false)
        val pattern = compile(query) ?: return invalid(query)
        val matches = ArrayList<Match>()
        var truncated = false
        val timedOut = guarded(pattern, text, timeoutMs) { matcher ->
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) continue
                if (matches.size == MAX_MATCHES) { truncated = true; break }
                matches += Match(matcher.start(), matcher.end())
            }
        }
        return timedOut ?: SearchResult.Found(matches, truncated)
    }

    /** [text] with every match replaced; a regex [replacement] may use `$0`-`$9`, `$$`, `\n`, `\t`, `\\`. */
    fun replaceAll(text: String, query: SearchQuery, replacement: String, timeoutMs: Long = TIMEOUT_MS): ReplaceResult {
        if (query.text.isEmpty()) return ReplaceResult.Replaced(text, 0)
        val pattern = compile(query) ?: return ReplaceResult.Failed(invalid(query))
        val out = StringBuilder(text.length)
        var last = 0
        var count = 0
        val failure = guarded(pattern, text, timeoutMs) { matcher ->
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) continue
                out.append(text, last, matcher.start())
                appendReplacement(out, matcher, replacement, query.regex)
                last = matcher.end()
                count++
            }
        }
        if (failure != null) return ReplaceResult.Failed(failure)
        out.append(text, last, text.length)
        return ReplaceResult.Replaced(out.toString(), count)
    }

    /**
     * What [match] (a match of [query] in [text]) is replaced with: [replacement] verbatim, or
     * with group references expanded in regex mode. Null when the pattern no longer matches
     * there, i.e. [match] is stale.
     */
    fun replacementFor(text: String, query: SearchQuery, match: Match, replacement: String): String? {
        if (!query.regex) return replacement
        val pattern = compile(query) ?: return null
        var result: String? = null
        guarded(pattern, text, TIMEOUT_MS) { matcher ->
            if (matcher.find(match.start) && matcher.start() == match.start && matcher.end() == match.end) {
                result = StringBuilder().also { appendReplacement(it, matcher, replacement, regex = true) }.toString()
            }
        }
        return result
    }

    /** Index of the first match starting at or after [offset], wrapping to 0; -1 when there are none. */
    fun firstFrom(matches: List<Match>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        var low = 0
        var high = matches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (matches[mid].start < offset) low = mid + 1 else high = mid
        }
        return if (low == matches.size) 0 else low
    }

    /** Index of the last match ending at or before [offset], wrapping to the last; -1 when there are none. */
    fun lastBefore(matches: List<Match>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        val index = matches.indexOfLast { it.end <= offset }
        return if (index < 0) matches.lastIndex else index
    }

    /** The next (or previous) index after [current], wrapping at either end. */
    fun step(size: Int, current: Int, forward: Boolean): Int = when {
        size == 0 -> -1
        current < 0 -> if (forward) 0 else size - 1
        forward -> (current + 1) % size
        else -> (current - 1 + size) % size
    }

    private fun compile(query: SearchQuery): Pattern? {
        var flags = Pattern.MULTILINE
        if (!query.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val body = if (query.regex) query.text else Pattern.quote(query.text)
        val source = if (query.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        return try {
            Pattern.compile(source, flags)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** Only reached for a pattern [compile] rejected, so it must be a regex the user typed. */
    private fun invalid(query: SearchQuery): SearchResult.InvalidPattern {
        val message = try {
            Pattern.compile(query.text)
            ""
        } catch (e: PatternSyntaxException) {
            e.description.orEmpty()
        }
        return SearchResult.InvalidPattern(message)
    }

    /** Runs [body] on a matcher over a deadline-checked view of [text]; a non-null result is a timeout. */
    private fun guarded(pattern: Pattern, text: CharSequence, timeoutMs: Long, body: (Matcher) -> Unit): SearchResult? =
        try {
            body(pattern.matcher(DeadlineChars(text, System.nanoTime() + timeoutMs * NANOS_PER_MS)))
            null
        } catch (_: SearchDeadlineExceeded) {
            SearchResult.TimedOut
        }

    private fun appendReplacement(out: StringBuilder, m: Matcher, template: String, regex: Boolean) {
        if (!regex) { out.append(template); return }
        var i = 0
        while (i < template.length) {
            val c = template[i]
            val next = template.getOrNull(i + 1)
            when {
                c == '$' && next == '$' -> { out.append('$'); i += 2 }
                c == '$' && next != null && next.isDigit() && next - '0' <= m.groupCount() -> {
                    out.append(m.group(next - '0').orEmpty()); i += 2
                }
                c == '\\' && next == 'n' -> { out.append('\n'); i += 2 }
                c == '\\' && next == 't' -> { out.append('\t'); i += 2 }
                c == '\\' && next == '\\' -> { out.append('\\'); i += 2 }
                else -> { out.append(c); i++ }
            }
        }
    }

    private const val NANOS_PER_MS = 1_000_000L
}

private class SearchDeadlineExceeded : RuntimeException(null, null, false, false)

/**
 * A [CharSequence] that throws once its deadline has passed. `java.util.regex` has no timeout
 * of its own but reads every character through `charAt`, which makes this the one place to
 * stop a runaway pattern. The clock is checked every [CHECK_EVERY] reads to stay cheap.
 */
private class DeadlineChars(private val inner: CharSequence, private val deadlineNanos: Long) : CharSequence {
    private var reads = 0

    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if (++reads % CHECK_EVERY == 0 && System.nanoTime() > deadlineNanos) throw SearchDeadlineExceeded()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = inner.subSequence(startIndex, endIndex)

    override fun toString(): String = inner.toString()

    private companion object {
        const val CHECK_EVERY = 4096
    }
}
