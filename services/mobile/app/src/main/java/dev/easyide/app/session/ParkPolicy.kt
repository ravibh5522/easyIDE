package dev.easyide.app.session

import android.content.ComponentCallbacks2

/** What the policy needs to know about one held workspace. */
data class HeldInfo(val id: String, val parked: Boolean, val lastActiveMs: Long)

/**
 * Which parked workspaces to end. The active one is never a candidate: ending it would be
 * ending what the user is looking at. Order is least recently used first, because the
 * workspace left longest ago is the one least likely to be returned to soon.
 */
object ParkPolicy {

    /** Parked workspaces beyond [limit], least recently active first. */
    fun overLimit(held: List<HeldInfo>, limit: Int): List<String> =
        lruParked(held).let { parked -> parked.take((parked.size - limit.coerceAtLeast(0)).coerceAtLeast(0)) }

    /** The parked workspaces to end for a `onTrimMemory(level)`: none, the LRU one, or all. */
    fun underPressure(level: Int, held: List<HeldInfo>): List<String> {
        val parked = lruParked(held)
        return when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> parked
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> emptyList()
            level >= CRITICAL_LEVEL -> parked.take(1)
            else -> emptyList()
        }
    }

    private fun lruParked(held: List<HeldInfo>): List<String> =
        held.filter { it.parked }.sortedBy { it.lastActiveMs }.map { it.id }

    /**
     * From `TRIM_MEMORY_RUNNING_CRITICAL` up, except `UI_HIDDEN` (a visibility signal, not
     * pressure). Below it the system is only asking apps to trim caches, which is what the
     * language-server runtime already does; ending a workspace's shells is a last resort.
     */
    @Suppress("DEPRECATION") // The running-* levels are deprecated but still delivered on the API levels we support.
    private const val CRITICAL_LEVEL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}
