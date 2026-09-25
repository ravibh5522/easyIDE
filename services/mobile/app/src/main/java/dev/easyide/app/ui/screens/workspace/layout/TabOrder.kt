package dev.easyide.app.ui.screens.workspace.layout

/** Which tabs a strip action closes. */
enum class CloseScope { THIS, OTHERS, TO_THE_RIGHT, ALL }

/** Pure tab-strip arithmetic: reordering, close sets, drag targets and scroll-into-view. */
object TabOrder {

    /** [list] with the element at [from] moved to [to]; indices out of range leave it unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The paths [scope] closes when the action is invoked on [path]. */
    fun closeSet(paths: List<String>, path: String, scope: CloseScope): List<String> {
        val index = paths.indexOf(path)
        if (index < 0) return emptyList()
        return when (scope) {
            CloseScope.THIS -> listOf(path)
            CloseScope.OTHERS -> paths.filter { it != path }
            CloseScope.TO_THE_RIGHT -> paths.drop(index + 1)
            CloseScope.ALL -> paths
        }
    }

    /**
     * Where a dragged tab lands: the index of the tab whose span holds the dragged tab's
     * centre after it has moved [offset] px from its resting place. [widths] are the tab
     * widths in strip order; the result is always a valid index.
     */
    fun dropIndex(widths: List<Float>, from: Int, offset: Float): Int {
        if (widths.isEmpty()) return 0
        val start = widths.take(from).sum()
        val centre = start + widths[from] / 2f + offset
        var edge = 0f
        widths.forEachIndexed { i, w ->
            edge += w
            if (centre < edge) return i
        }
        return widths.lastIndex
    }

    /**
     * How far to scroll (positive = towards the end) so the item spanning [itemStart, itemEnd]
     * is fully inside the viewport [viewStart, viewEnd]; 0 when it already is. An item wider
     * than the viewport aligns its start.
     */
    fun scrollToReveal(itemStart: Float, itemEnd: Float, viewStart: Float, viewEnd: Float): Float = when {
        itemStart < viewStart -> itemStart - viewStart
        itemEnd > viewEnd -> minOf(itemEnd - viewEnd, itemStart - viewStart)
        else -> 0f
    }
}
