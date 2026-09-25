package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/** Dividers only separate: none first, last or doubled, at every level. Extension-fed menus can produce any of those. */
internal fun tidyMenu(items: List<KitMenuItem>): List<KitMenuItem> {
    val out = ArrayList<KitMenuItem>(items.size)
    for (item in items) {
        val next = if (item is KitMenuItem.Submenu) item.copy(items = tidyMenu(item.items)) else item
        if (next !is KitMenuItem.Divider || (out.isNotEmpty() && out.last() !is KitMenuItem.Divider)) out += next
    }
    if (out.lastOrNull() is KitMenuItem.Divider) out.removeAt(out.lastIndex)
    return out
}

/** Below the anchor, start-aligned; flipped above when it would run off the bottom; clamped inside the window. */
internal fun menuPosition(anchor: IntRect, window: IntSize, popup: IntSize): IntOffset {
    val below = anchor.bottom
    val y = if (below + popup.height <= window.height) below else maxOf(anchor.top - popup.height, 0)
    val x = anchor.left.coerceIn(0, maxOf(window.width - popup.width, 0))
    return IntOffset(x, y)
}

/**
 * A submenu opens beside its parent row, level with it: on the right, or on the left when the right
 * would leave the window and the left fits. When neither side fits (a narrow window) it opens below the
 * row, start-aligned with the parent. Always clamped inside the window.
 */
internal fun submenuPosition(row: IntRect, parent: IntRect, window: IntSize, popup: IntSize): IntOffset {
    val maxY = maxOf(window.height - popup.height, 0)
    val beside = row.top.coerceIn(0, maxY)
    return when {
        parent.right + popup.width <= window.width -> IntOffset(parent.right, beside)
        parent.left - popup.width >= 0 -> IntOffset(parent.left - popup.width, beside)
        else -> menuPosition(IntRect(parent.left, row.top, parent.right, row.bottom), window, popup)
    }
}

/** True when [items] has an entry that can be focused: an enabled action or submenu. */
internal fun KitMenuItem.focusable(): Boolean = when (this) {
    is KitMenuItem.Action -> enabled
    is KitMenuItem.Submenu -> enabled
    KitMenuItem.Divider -> false
}

/** The next focusable index from [from] in direction [step] (+1 or -1), wrapping; -1 when nothing can be focused. From -1 it starts at the first (or last). */
internal fun nextFocusable(items: List<KitMenuItem>, from: Int, step: Int): Int {
    if (items.none { it.focusable() }) return -1
    var i = from
    repeat(items.size) {
        i = if (i < 0) (if (step > 0) 0 else items.lastIndex) else Math.floorMod(i + step, items.size)
        if (items[i].focusable()) return i
    }
    return -1
}

/**
 * Keyboard focus through nested menus: [path] holds the focused index at each open level, so its size
 * is the number of open levels and the last entry is where the arrow keys act. Every move returns a new
 * state; the composable only renders it. Empty means the menu just opened with nothing focused.
 */
internal data class MenuNav(val path: List<Int> = emptyList()) {
    fun itemsAt(root: List<KitMenuItem>, level: Int): List<KitMenuItem> {
        var items = root
        for (i in 0 until level) items = (items[path[i]] as KitMenuItem.Submenu).items
        return items
    }

    private fun deepest(root: List<KitMenuItem>) = itemsAt(root, maxOf(path.size - 1, 0))

    fun focused(root: List<KitMenuItem>): KitMenuItem? = path.lastOrNull()?.let { deepest(root).getOrNull(it) }

    fun move(root: List<KitMenuItem>, step: Int): MenuNav {
        val items = deepest(root)
        val next = nextFocusable(items, path.lastOrNull() ?: -1, step)
        return if (next < 0) this else MenuNav(path.dropLast(1) + next)
    }

    /** Right arrow: open the focused submenu with its first entry focused; anything else stays. */
    fun open(root: List<KitMenuItem>): MenuNav {
        val sub = focused(root) as? KitMenuItem.Submenu ?: return this
        val first = nextFocusable(sub.items, -1, 1)
        return if (sub.enabled && first >= 0) MenuNav(path + first) else this
    }

    /** Left arrow: close the deepest submenu, leaving focus on the row that opened it. */
    fun close(): MenuNav = if (path.size > 1) MenuNav(path.dropLast(1)) else this

    /** Pointer hover: focus [index] at [level], closing anything deeper; hovering a submenu row opens it with nothing inside focused. */
    fun hover(root: List<KitMenuItem>, level: Int, index: Int): MenuNav {
        val here = path.take(level) + index
        val row = MenuNav(here).itemsAt(root, level).getOrNull(index)
        return MenuNav(if (row is KitMenuItem.Submenu && row.enabled) here + NOTHING else here)
    }

    companion object {
        /** The focused index of a level that is open but has no focus yet. */
        const val NOTHING = -1
    }

    /** Home and End. */
    fun edge(root: List<KitMenuItem>, last: Boolean): MenuNav {
        val items = deepest(root)
        val next = nextFocusable(items, -1, if (last) -1 else 1)
        return if (next < 0) this else MenuNav(path.dropLast(1) + next)
    }
}
