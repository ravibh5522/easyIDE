package dev.easyide.lsp.manager

import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState

/** The kill-order steps of lsp-lifecycle.md 3.2, in order. */
enum class EvictionStep { IDLE, NOT_VISIBLE, PRESSURE_HOOKS, FOCUSED }

/** One entry of the kill order: a session to stop, or the point where other modules shed memory. */
sealed interface Victim {
    val step: EvictionStep

    data class Session(val key: ServerKey, override val step: EvictionStep) : Victim
    data object PressureHooks : Victim {
        override val step: EvictionStep get() = EvictionStep.PRESSURE_HOOKS
    }
}

/**
 * What the kill order needs to know about a session.
 *
 * @property hasVisibleDoc one of its open documents is on screen.
 * @property servesFocused it has the focused editor's document open.
 */
data class SessionView(
    val key: ServerKey,
    val state: SessionState,
    val lastUsedAt: Long,
    val rssKb: Long?,
    val hasVisibleDoc: Boolean,
    val servesFocused: Boolean,
)

/** Another module's way to shed memory at step 3 (the WASM host drops idle instances). */
fun interface MemoryPressureHook {
    suspend fun onMemoryPressure()
}

/**
 * Android memory signals, mapped by the app from `ComponentCallbacks2` levels so `:lsp` holds
 * no platform constants: RUNNING_LOW (and worse, while foreground), UI_HIDDEN, BACKGROUND (and
 * MODERATE), COMPLETE.
 */
enum class MemoryPressure(val steps: Set<EvictionStep>) {
    RUNNING_LOW(setOf(EvictionStep.IDLE, EvictionStep.NOT_VISIBLE)),
    UI_HIDDEN(setOf(EvictionStep.IDLE)),
    BACKGROUND(setOf(EvictionStep.IDLE, EvictionStep.NOT_VISIBLE, EvictionStep.PRESSURE_HOOKS)),
    COMPLETE(EvictionStep.entries.toSet()),
}

/**
 * The single kill order (arch.md 7.7, exact): idle sessions LRU first; running sessions with
 * no visible document, LRU first; then other modules' pressure hooks; then the sessions
 * serving the focused editor, largest RSS first. Running sessions that only serve a visible,
 * unfocused document are never listed, and neither are terminals or editor buffers - they are
 * not sessions (R-PERF-03).
 */
object MemoryPolicy {

    fun evictionOrder(sessions: List<SessionView>): List<Victim> {
        val idle = sessions.filter { it.state is SessionState.Idle }.sortedBy { it.lastUsedAt }
        val running = sessions.filter { it.state is SessionState.Running }
        val hidden = running.filter { !it.hasVisibleDoc && !it.servesFocused }.sortedBy { it.lastUsedAt }
        val focused = running.filter { it.servesFocused }.sortedByDescending { it.rssKb ?: 0 }
        return idle.map { Victim.Session(it.key, EvictionStep.IDLE) } +
            hidden.map { Victim.Session(it.key, EvictionStep.NOT_VISIBLE) } +
            Victim.PressureHooks +
            focused.map { Victim.Session(it.key, EvictionStep.FOCUSED) }
    }
}
