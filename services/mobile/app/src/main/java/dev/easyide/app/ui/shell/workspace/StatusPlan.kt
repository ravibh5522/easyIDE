package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.foundation.WidthClass

/** The parts of the status strip, from what a phone keeps last to what it drops first. */
enum class StatusId(val priority: Int) {
    PROBLEMS(100),
    SAVE(95),
    FILE(90),
    EXT_RIGHT(50),
    EXT_LEFT(40),
    LINES(30),
    PROJECT(20),
}

/** One part as measured: its natural [width], and the least it can be squeezed to (a name truncates, an icon row does not). */
data class StatusSlot(val id: StatusId, val width: Float, val minWidth: Float = width)

/**
 * What the status strip shows in a given width (layout-spec.md sections 4.1 and 5). It never
 * overlaps: parts are kept in priority order while they fit at their minimum width, the rest are
 * dropped whole, and what is left over grows the truncatable ones back towards their natural
 * width. Units are whatever the caller measures in (px), as long as they are the same throughout.
 */
object StatusPlan {

    /** The parts a width class considers at all: a phone drops the project name and the line count before any fitting. */
    fun allowed(width: WidthClass): Set<StatusId> = when (width) {
        WidthClass.COMPACT -> setOf(StatusId.PROBLEMS, StatusId.SAVE, StatusId.FILE, StatusId.EXT_RIGHT, StatusId.EXT_LEFT)
        WidthClass.MEDIUM -> StatusId.entries.toSet() - StatusId.PROJECT
        WidthClass.EXPANDED -> StatusId.entries.toSet()
    }

    /** The width each kept slot gets; a slot missing from the result is dropped. [gap] separates neighbours. */
    fun fit(slots: List<StatusSlot>, available: Float, gap: Float): Map<StatusId, Float> {
        val byPriority = slots.sortedByDescending { it.id.priority }
        val widths = LinkedHashMap<StatusId, Float>()
        var used = 0f
        for (slot in byPriority) {
            val need = slot.minWidth + if (widths.isEmpty()) 0f else gap
            if (used + need <= available) {
                widths[slot.id] = slot.minWidth
                used += need
            }
        }
        var spare = available - used
        for (slot in byPriority) {
            val current = widths[slot.id] ?: continue
            val grow = minOf(slot.width - current, spare)
            if (grow > 0f) {
                widths[slot.id] = current + grow
                spare -= grow
            }
        }
        return widths
    }
}
