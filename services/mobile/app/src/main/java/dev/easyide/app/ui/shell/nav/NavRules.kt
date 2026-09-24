package dev.easyide.app.ui.shell.nav

import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavLayout
import dev.easyide.app.ui.shell.ShellLimits

/** `shell.navigation.position`. Stored by [id] so R8 renaming never changes a saved file. */
enum class NavPosition(val id: String) { AUTO("auto"), LEFT("left"), RIGHT("right"), BOTTOM("bottom") }

/** `shell.navigation.labels`. */
enum class NavLabels(val id: String) { AUTO("auto"), ALWAYS("always"), NEVER("never") }

/** Where the navigation surface is drawn. */
enum class NavPlacement { BOTTOM, RAIL_START, RAIL_END }

/** What a navigation item's badge shows: a dot, or a number. */
sealed interface NavBadgeValue {
    data object Dot : NavBadgeValue
    data class Count(val n: Int) : NavBadgeValue
}

/** A bottom bar or rail after overflow: the cells shown, what "More" holds, and whether the active item hides in it. */
data class NavCells(val shown: List<NavItem>, val more: List<NavItem>, val moreActive: Boolean) {
    val hasMore: Boolean get() = more.isNotEmpty()
}

/** Every decision the navigation surface makes that is not drawing (shell-model.md section 6.2). */
object NavRules {

    /** Auto is the bar on a phone and a rail on anything wider (shell-model.md 6.2); the other values are the user's choice. */
    fun placement(position: NavPosition, width: WidthClass): NavPlacement = when (position) {
        NavPosition.AUTO -> if (width.isCompact) NavPlacement.BOTTOM else NavPlacement.RAIL_START
        NavPosition.LEFT -> NavPlacement.RAIL_START
        NavPosition.RIGHT -> NavPlacement.RAIL_END
        NavPosition.BOTTOM -> NavPlacement.BOTTOM
    }

    /** The bottom bar steps aside while the software keyboard is up: the input dock takes its place (shell-model.md 9). A rail never does. */
    fun barShown(placement: NavPlacement, keyboardUp: Boolean): Boolean = placement != NavPlacement.BOTTOM || !keyboardUp

    /** Auto labels the bottom bar always (it is thumb-reached and has room) and a rail only when the window is expanded. */
    fun showLabels(labels: NavLabels, placement: NavPlacement, width: WidthClass): Boolean = when (labels) {
        NavLabels.ALWAYS -> true
        NavLabels.NEVER -> false
        NavLabels.AUTO -> placement == NavPlacement.BOTTOM || width.isExpanded
    }

    /** Cells a bottom bar can hold: at most [ShellLimits.COMPACT_NAV_CAPACITY], fewer when the window is too narrow for 44dp each. */
    fun barCapacity(widthDp: Float, cellDp: Float): Int =
        (widthDp / cellDp).toInt().coerceIn(1, ShellLimits.COMPACT_NAV_CAPACITY)

    /** Cells a rail can hold in [heightDp]; a rail has no fixed maximum, only its height. */
    fun railCapacity(heightDp: Float, cellDp: Float): Int = (heightDp / cellDp).toInt().coerceAtLeast(1)

    /**
     * Fits [items] into [capacity] cells. When they do not all fit the last cell becomes "More",
     * so the surface never exceeds [capacity]. Pinned items keep their cell ([NavLayout.split]).
     */
    fun cells(items: List<NavItem>, capacity: Int, pinned: Set<String>, active: String?): NavCells {
        if (items.size <= capacity) return NavCells(items, emptyList(), moreActive = false)
        val split = NavLayout.split(items, (capacity - 1).coerceAtLeast(1), pinned)
        return NavCells(split.visible, split.more, moreActive = split.more.any { it.id == active })
    }

    /** A count over [BADGE_MAX] reads "99+" so the badge never outgrows its cell; zero shows nothing. */
    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > BADGE_MAX -> "$BADGE_MAX+"
        else -> count.toString()
    }

    const val BADGE_MAX = 99
}
