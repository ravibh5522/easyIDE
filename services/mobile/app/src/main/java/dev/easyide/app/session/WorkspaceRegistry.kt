package dev.easyide.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A workspace as the registry sees it: something holding processes and buffers that can be
 * left running, saved, and ended.
 */
interface HeldSession {
    /** Writes the recoverable state (tabs, layout, unsaved buffers) to disk. Cheap when nothing changed. */
    suspend fun saveBackup()

    /** No longer on screen; still running. */
    fun onParked()

    /** On screen again. */
    fun onResumed()

    /**
     * Stops every process, cancels every job and releases the session for good. [discardStored]
     * also deletes what [saveBackup] wrote: an explicit close that the user confirmed must not
     * bring the closed buffers back next time.
     */
    fun end(discardStored: Boolean)
}

/** Proof that a screen attached a workspace; only the newest holder's release parks it (see [WorkspaceRegistry.park]). */
@JvmInline
value class Lease(internal val id: Long)

/**
 * Owns the live workspaces of the process, keyed by project id, instead of the navigation
 * entry: leaving a project **parks** it (shells keep running, buffers stay in memory) and
 * entering it again re-attaches. Decisions are in docs/decision/0023-workspace-session-lifetime.md.
 *
 *  - At most one workspace is active; attaching another parks it first.
 *  - More than [parkLimit] parked workspaces: the least recently used ones are saved and
 *    ended ([ParkPolicy.overLimit]); under memory pressure likewise ([onTrimMemory]).
 *  - [close] is the explicit end: it discards the stored session as well.
 *
 * Main-thread confined (composition and lifecycle callbacks); [scope] must be a main-thread
 * scope. Only the sessions' own `saveBackup` hops to another dispatcher.
 */
class WorkspaceRegistry<S : HeldSession>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val parkLimit: () -> Int,
    /** Builds a session. [settled] is the previous same-project session still saving on its way out, if any: restore must wait for it. */
    private val open: (projectId: String, environmentId: String, settled: Job?) -> S,
) {
    private class Entry<S>(val session: S, val environmentId: String, var lastActiveMs: Long) {
        var parked = true
        var lease = 0L
    }

    private val entries = LinkedHashMap<String, Entry<S>>()
    private val settling = HashMap<String, Job>()
    private var leases = 0L

    private val liveState = MutableStateFlow<Set<String>>(emptySet())

    /** Project ids with a live session, active or parked. */
    val liveIds: StateFlow<Set<String>> = liveState.asStateFlow()

    /**
     * The session of [projectId], created if none is live. Does not change active or parked
     * state: the screen attaches it once it is actually composed.
     */
    fun obtain(projectId: String, environmentId: String): S {
        val existing = entries[projectId]
        if (existing != null && existing.environmentId == environmentId) return existing.session
        // The project was moved to another environment: its shells run in the old one.
        if (existing != null) evict(projectId)
        val session = open(projectId, environmentId, settling[projectId])
        entries[projectId] = Entry(session, environmentId, clock())
        publish()
        return session
    }

    /** Makes [projectId] the active workspace, parking whichever was. Null when it is not live. */
    fun attach(projectId: String): Lease? {
        val entry = entries[projectId] ?: return null
        // The newcomer is marked active before anything is parked, so the limit can never pick it.
        val leaving = entries.values.filter { it !== entry && !it.parked }
        entry.parked = false
        entry.lastActiveMs = clock()
        entry.lease = ++leases
        leaving.forEach(::parkEntry)
        entry.session.onResumed()
        enforceLimit()
        return Lease(entry.lease)
    }

    /**
     * Parks [projectId] if [lease] is still the newest attach. A screen that is still
     * animating out must not park the workspace its replacement just re-attached.
     */
    fun park(projectId: String, lease: Lease) {
        val entry = entries[projectId] ?: return
        if (entry.parked || entry.lease != lease.id) return
        parkEntry(entry)
        enforceLimit()
    }

    /** The user's explicit close: ends the session and forgets what it stored. */
    fun close(projectId: String) {
        val entry = entries.remove(projectId) ?: return
        entry.session.end(discardStored = true)
        publish()
    }

    /** Ends every session, saving each first: the user asked for everything to stop. */
    fun endAll() {
        entries.keys.toList().forEach(::evict)
    }

    /** `ComponentCallbacks2.onTrimMemory`: ends the parked workspaces the policy picks, saving each first. */
    fun onTrimMemory(level: Int) {
        ParkPolicy.underPressure(level, infos()).forEach(::evict)
    }

    /** Saves every live session (app leaving the foreground). Sequential: the writes are small and share one disk. */
    suspend fun flushAll() {
        entries.values.map { it.session }.forEach { it.saveBackup() }
    }

    private fun parkEntry(entry: Entry<S>) {
        entry.parked = true
        entry.lastActiveMs = clock()
        entry.session.onParked()
        scope.launch { entry.session.saveBackup() }
    }

    private fun enforceLimit() {
        ParkPolicy.overLimit(infos(), parkLimit()).forEach(::evict)
    }

    /** Save, then end, without discarding: what was on screen an hour ago must still be recoverable. */
    private fun evict(projectId: String) {
        val entry = entries.remove(projectId) ?: return
        publish()
        val job = scope.launch {
            entry.session.saveBackup()
            entry.session.end(discardStored = false)
        }
        settling[projectId] = job
        job.invokeOnCompletion { settling.remove(projectId, job) }
    }

    private fun infos(): List<HeldInfo> = entries.map { (id, e) -> HeldInfo(id, e.parked, e.lastActiveMs) }

    private fun publish() {
        liveState.value = entries.keys.toSet()
    }
}
