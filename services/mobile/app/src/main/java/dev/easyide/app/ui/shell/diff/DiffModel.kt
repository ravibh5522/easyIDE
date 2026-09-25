package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.screens.workspace.git.DiffLayout
import dev.easyide.app.ui.screens.workspace.git.DiffRow
import dev.easyide.app.ui.screens.workspace.git.SplitRow
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.FileDiff

/**
 * Unified lines fit any width; two columns need room for two lines of code, so side by side starts
 * at the expanded breakpoint. It is measured on the document itself, not the window: a document in
 * a half-width group of an expanded window stays unified.
 */
enum class DiffMode {
    UNIFIED,
    SIDE_BY_SIDE;

    companion object {
        const val SPLIT_MIN_DP = 840f

        fun of(widthDp: Float): DiffMode = if (widthDp >= SPLIT_MIN_DP) SIDE_BY_SIDE else UNIFIED
    }
}

/** One entry of the flattened diff, so a lazy list virtualises lines, not hunks. */
sealed interface DiffItem {
    data class Header(val index: Int, val hunk: DiffHunk) : DiffItem
    data class Unified(val row: DiffRow) : DiffItem
    data class Split(val row: SplitRow) : DiffItem
}

/**
 * A text diff laid out for one [DiffMode]: the [items] in order, where each hunk starts ([headers], as item
 * indices), and the numbered [rows] of every hunk (the syntax pass needs them apart from the layout).
 * Hunk navigation is arithmetic on item indices, so it works whatever the list has scrolled to.
 */
class DiffModel private constructor(val items: List<DiffItem>, val headers: List<Int>, val rows: List<List<DiffRow>>) {

    /** The header of the hunk after the one [from] is in, or null past the last. */
    fun nextHunk(from: Int): Int? = headers.firstOrNull { it > from }

    /** The header of the hunk [from] is inside, or of the one before when [from] is already on a header. */
    fun previousHunk(from: Int): Int? = headers.lastOrNull { it < from }

    /** Which hunk [from] is in, counted from 0; -1 above the first header. */
    fun hunkAt(from: Int): Int = headers.indexOfLast { it <= from }

    companion object {
        fun of(diff: FileDiff.Text, mode: DiffMode): DiffModel {
            val rows = diff.hunks.map(DiffLayout::rows)
            val items = ArrayList<DiffItem>()
            val headers = ArrayList<Int>(diff.hunks.size)
            diff.hunks.forEachIndexed { i, hunk ->
                headers += items.size
                items += DiffItem.Header(i, hunk)
                when (mode) {
                    DiffMode.UNIFIED -> rows[i].forEach { items += DiffItem.Unified(it) }
                    DiffMode.SIDE_BY_SIDE -> DiffLayout.split(rows[i]).forEach { items += DiffItem.Split(it) }
                }
            }
            return DiffModel(items, headers, rows)
        }
    }
}
