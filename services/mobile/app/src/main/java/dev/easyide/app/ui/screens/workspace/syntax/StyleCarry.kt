package dev.easyide.app.ui.screens.workspace.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [stale]'s colours laid over [current], which differs from it by one edit.
 *
 * A colouring pass lags the keystroke by the debounce plus the pass itself. Showing the plain text
 * in that gap made every colour vanish and return on each keystroke. The edit is the span between
 * the common prefix and suffix of the two texts: colours before it stay, colours after it shift by
 * the length change, a colour that covers the whole edit stretches over it (typing inside a word,
 * string or comment keeps its colour), and one that only partly overlaps keeps the parts outside.
 */
internal fun carryStyles(stale: AnnotatedString, current: String): AnnotatedString {
    val old = stale.text
    val shared = minOf(old.length, current.length)
    var prefix = 0
    while (prefix < shared && old[prefix] == current[prefix]) prefix++
    var suffix = 0
    while (suffix < shared - prefix && old[old.length - 1 - suffix] == current[current.length - 1 - suffix]) suffix++
    val oldEditEnd = old.length - suffix
    val delta = current.length - old.length

    return buildAnnotatedString {
        append(current)
        for (span in stale.spanStyles) {
            when {
                span.start <= prefix && span.end >= oldEditEnd -> add(span.item, span.start, span.end + delta)
                span.end <= prefix -> add(span.item, span.start, span.end)
                span.start >= oldEditEnd -> add(span.item, span.start + delta, span.end + delta)
                else -> {
                    if (span.start < prefix) add(span.item, span.start, prefix)
                    if (span.end > oldEditEnd) add(span.item, oldEditEnd + delta, span.end + delta)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.add(style: androidx.compose.ui.text.SpanStyle, start: Int, end: Int) {
    if (end > start) addStyle(style, start, end)
}
