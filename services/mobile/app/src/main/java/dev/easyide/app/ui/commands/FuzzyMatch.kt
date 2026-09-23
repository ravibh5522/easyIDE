package dev.easyide.app.ui.commands

/**
 * Subsequence match for the palette: every query character must appear in
 * [text] in order, case-insensitively. Returns null for no match, otherwise a
 * score where higher is better - consecutive runs and word starts score extra,
 * so "tt" ranks "Toggle Terminal" (two word starts) above "Next Editor Tab".
 * Greedy (first occurrence of each character), which is enough for command
 * titles of a few words.
 */
fun fuzzyScore(query: String, text: String): Int? {
    if (query.isEmpty()) return 0
    var score = 0
    var q = 0
    var previousMatch = -2
    for (i in text.indices) {
        if (q == query.length) break
        if (!text[i].equals(query[q], ignoreCase = true)) continue
        score += MATCH_SCORE
        if (i == previousMatch + 1) score += CONSECUTIVE_BONUS
        if (i == 0 || !text[i - 1].isLetterOrDigit()) score += WORD_START_BONUS
        previousMatch = i
        q++
    }
    return if (q == query.length) score else null
}

/** [items] matching [query], best first; ties keep their original order. */
fun <T> fuzzyFilter(query: String, items: List<T>, textOf: (T) -> String): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return items
    return items
        .mapNotNull { item -> fuzzyScore(trimmed, textOf(item))?.let { item to it } }
        .sortedByDescending { it.second }
        .map { it.first }
}

private const val MATCH_SCORE = 1
private const val CONSECUTIVE_BONUS = 3
private const val WORD_START_BONUS = 5
