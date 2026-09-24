package dev.easyide.app.ui.screens.workspace.decor

/**
 * One contiguous replacement: `[start, oldEnd)` of the old text became `[start, newEnd)` of
 * the new text. Everything before [start] is untouched; everything at or after [oldEnd]
 * moved by [delta].
 */
data class OffsetEdit(val start: Int, val oldEnd: Int, val newEnd: Int) {

    val delta: Int get() = newEnd - oldEnd

    private val isInsertion: Boolean get() = start == oldEnd

    /**
     * Where [offset] lands when it sticks to the character after it: an insertion exactly
     * at the offset pushes it right, a replaced offset lands after the replacement. Used for
     * range starts and points, so a range never grows at its start.
     */
    fun mapAfter(offset: Int): Int = when {
        offset < start -> offset
        offset >= oldEnd -> offset + delta
        else -> newEnd
    }

    /**
     * Where [offset] lands when it sticks to the character before it: an insertion exactly
     * at the offset leaves it, a replaced offset falls back to [start]. Used for range ends,
     * so typing right after a squiggled word does not extend the squiggle.
     */
    fun mapBefore(offset: Int): Int = when {
        offset <= start -> offset
        offset > oldEnd -> offset + delta
        else -> start
    }

    /**
     * True when the edit changes text inside `[rangeStart, rangeEnd)`. Touching an edge is
     * not overlap: inserting right before or after a range leaves its text intact. A point
     * overlaps only a replacement that strictly surrounds it.
     */
    fun overlaps(rangeStart: Int, rangeEnd: Int): Boolean = when {
        rangeStart == rangeEnd -> start < rangeStart && rangeStart < oldEnd
        isInsertion -> rangeStart < start && start < rangeEnd
        else -> start < rangeEnd && oldEnd > rangeStart
    }

    companion object {
        /**
         * The single replacement that turns [old] into [new], from their common prefix and
         * suffix, or null when they are equal. O(length of the unchanged ends), the same
         * cost `DocumentHighlighter.setContent` already pays per keystroke.
         *
         * Never splits a surrogate pair: replacing one emoji with another that shares its
         * high surrogate would otherwise start the edit between the two halves, and a
         * decoration boundary there would point into the middle of a character.
         */
        fun between(old: CharSequence, new: CharSequence): OffsetEdit? {
            if (old === new) return null
            val shorter = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < shorter && old[prefix] == new[prefix]) prefix++
            if (prefix == old.length && prefix == new.length) return null
            if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--

            val maxSuffix = shorter - prefix
            var suffix = 0
            while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--

            return OffsetEdit(start = prefix, oldEnd = old.length - suffix, newEnd = new.length - suffix)
        }
    }
}
