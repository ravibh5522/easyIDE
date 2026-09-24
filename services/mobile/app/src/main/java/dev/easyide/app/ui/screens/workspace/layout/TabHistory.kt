package dev.easyide.app.ui.screens.workspace.layout

/**
 * Most-recently-used order of the open tabs: what closing a tab falls back to, and what
 * Ctrl+Tab cycles through (like every desktop editor, not left-to-right tab order).
 *
 * It follows the workspace state rather than intercepting each of the many places a tab
 * becomes active: [sync] is fed the open paths and the active one and reorders itself.
 * Not thread safe; the workspace touches it from the main thread only.
 */
class TabHistory {

    /** Paths, most recently active first. */
    private val order = ArrayList<String>()
    private var active: String? = null

    /** The MRU snapshot a run of quick [cycle] presses walks through, and where in it we are. */
    private var session: List<String> = emptyList()
    private var sessionIndex = 0
    private var sessionTarget: String? = null
    private var lastCycleMs = 0L

    /** Open paths in MRU order, for tests and the cycle snapshot. */
    val recent: List<String> get() = order.toList()

    /**
     * Brings the history in line with the workspace: closed tabs leave it, new ones join at
     * the back, and the active tab moves to the front - unless it is where a [cycle] just put
     * us, so stepping through several tabs does not reshuffle the list under the cursor.
     */
    fun sync(open: Collection<String>, newActive: String?) {
        order.retainAll(open.toSet())
        open.filter { it !in order }.forEach(order::add)
        active = newActive
        if (newActive == null || newActive !in order) return
        if (newActive == sessionTarget) return
        sessionTarget = null
        order.remove(newActive)
        order.add(0, newActive)
    }

    /**
     * The tab a Ctrl+Tab ([step] = 1) or Ctrl+Shift+Tab (-1) press should activate, or null
     * with fewer than two tabs. Presses within [CYCLE_WINDOW_MS] of each other keep walking
     * the same snapshot, so holding Ctrl and tapping Tab reaches the third and fourth most
     * recent tab; a pause commits the current tab to the front and starts over, so a single
     * press always flips between the two latest.
     */
    fun cycle(step: Int, nowMs: Long): String? {
        if (order.size < 2) return null
        if (sessionTarget == null || nowMs - lastCycleMs > CYCLE_WINDOW_MS) {
            active?.takeIf { it in order }?.let { order.remove(it); order.add(0, it) }
            session = order.toList()
            sessionIndex = 0
        }
        sessionIndex = Math.floorMod(sessionIndex + step, session.size)
        lastCycleMs = nowMs
        return session[sessionIndex].also { sessionTarget = it }
    }

    /**
     * The tab to show once [closed] are gone: the current one if it survives, else the most
     * recently used survivor, else the tab that took the closed one's place in [tabOrder]
     * (its right neighbour, or the left when it was last).
     */
    fun activeAfterClosing(closed: Set<String>, current: String?, tabOrder: List<String>): String? {
        if (current != null && current !in closed) return current
        order.firstOrNull { it !in closed && it in tabOrder }?.let { return it }
        val survivors = tabOrder.filter { it !in closed }
        val index = tabOrder.indexOf(current).takeIf { it >= 0 } ?: return survivors.lastOrNull()
        val leftOfIt = tabOrder.take(index).count { it !in closed }
        return survivors.getOrNull(leftOfIt) ?: survivors.lastOrNull()
    }

    companion object {
        /** Gap between two Tab presses that still counts as one Ctrl-held cycling gesture. */
        const val CYCLE_WINDOW_MS = 1_000L
    }
}
