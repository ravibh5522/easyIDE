package dev.easyide.app.ui.screens.workspace.files

/** A path that matched, with the offsets of the matched characters (for highlighting). */
class PathHit(val path: String, val score: Int, val matched: IntArray) {
    val fileName: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")
}

/**
 * Fuzzy file finding for quick open: every query character must appear in the path in order
 * (case-insensitively), and the score prefers what people mean by a short query.
 *
 *  - a match inside the file name beats one spread across the directories ("edpane" finds
 *    `EditorPane.kt` before `editor/panes/x.kt`), and a file name that starts with the query
 *    beats one that merely contains it;
 *  - runs of consecutive characters, and characters at the start of a word (after `/ . _ - `
 *    or a lower-to-upper camel hump), score extra;
 *  - the alignment is the tightest window rather than the first greedy one, so "ep" does not
 *    match the `e` of a directory and the `p` of the file name when `EditorPane` has both;
 *  - a recently opened file gets a bonus that decays with its place in the recent list, so on
 *    equal matches the file you were just in comes first.
 *
 * Cost is one pass per path (subsequence test, then scoring only the survivors), which keeps a
 * 50,000-file project responsive per keystroke on a tablet.
 */
object PathMatcher {

    fun score(query: String, path: String): PathHit? {
        val q = query.filterNot(Char::isWhitespace)
        if (q.isEmpty()) return PathHit(path, 0, IntArray(0))
        val nameStart = path.lastIndexOf('/') + 1
        window(q, path, nameStart)?.let { return hit(q, path, it, inName = true) }
        return window(q, path, 0)?.let { hit(q, path, it, inName = false) }
    }

    /** [paths] matching [query], best first, at most [limit]; recent files break ties and lead an empty query. */
    fun rank(query: String, paths: List<String>, recent: List<String>, limit: Int): List<PathHit> {
        val recency = recent.withIndex().associate { (index, path) -> path to index }
        if (query.isBlank()) {
            val known = paths.toHashSet()
            val recentFirst = recent.filter { it in known }
            val rest = paths.asSequence().filter { it !in recency }.take((limit - recentFirst.size).coerceAtLeast(0))
            return (recentFirst.asSequence() + rest).take(limit).map { PathHit(it, 0, IntArray(0)) }.toList()
        }
        return paths.asSequence()
            .mapNotNull { path -> score(query, path)?.let { hit -> withRecency(hit, recency[path]) } }
            .sortedWith(compareByDescending<PathHit> { it.score }.thenBy { it.path.length }.thenBy { it.path })
            .take(limit)
            .toList()
    }

    private fun withRecency(hit: PathHit, rank: Int?): PathHit =
        if (rank == null) hit else PathHit(hit.path, hit.score + (RECENT_BONUS - rank * RECENT_DECAY).coerceAtLeast(0), hit.matched)

    /**
     * The tightest window `[first, last]` in `path[from..]` containing [q] as a subsequence:
     * a forward greedy pass finds where the match can end, a backward pass from there finds
     * the latest start. Null when [q] is not a subsequence.
     */
    private fun window(q: String, path: String, from: Int): IntArray? {
        var qi = 0
        var end = -1
        for (i in from until path.length) {
            if (path[i].equals(q[qi], ignoreCase = true) && ++qi == q.length) { end = i; break }
        }
        if (end < 0) return null
        val positions = IntArray(q.length)
        qi = q.length - 1
        var i = end
        while (qi >= 0) {
            if (path[i].equals(q[qi], ignoreCase = true)) positions[qi--] = i
            i--
        }
        return positions
    }

    private fun hit(q: String, path: String, positions: IntArray, inName: Boolean): PathHit {
        val nameStart = path.lastIndexOf('/') + 1
        var score = if (inName) NAME_BONUS else 0
        for ((k, pos) in positions.withIndex()) {
            score += CHAR_SCORE
            if (k > 0 && pos == positions[k - 1] + 1) score += CONSECUTIVE_BONUS
            if (isWordStart(path, pos)) score += WORD_START_BONUS
            if (path[pos] == q[k]) score += CASE_BONUS
        }
        if (inName && positions[0] == nameStart) score += NAME_PREFIX_BONUS
        // Shorter paths first among otherwise equal matches, without ever outweighing quality.
        score -= (path.length - positions.size).coerceAtMost(LENGTH_PENALTY_CAP) / LENGTH_PENALTY_DIVISOR
        return PathHit(path, score, positions)
    }

    private fun isWordStart(path: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = path[index - 1]
        return previous in WORD_SEPARATORS || (previous.isLowerCase() && path[index].isUpperCase())
    }

    private const val WORD_SEPARATORS = "/._- "
    private const val CHAR_SCORE = 2
    private const val CONSECUTIVE_BONUS = 6
    private const val WORD_START_BONUS = 8
    private const val CASE_BONUS = 1
    private const val NAME_BONUS = 40
    private const val NAME_PREFIX_BONUS = 30
    private const val LENGTH_PENALTY_CAP = 60
    private const val LENGTH_PENALTY_DIVISOR = 4
    private const val RECENT_BONUS = 24
    private const val RECENT_DECAY = 2
}
