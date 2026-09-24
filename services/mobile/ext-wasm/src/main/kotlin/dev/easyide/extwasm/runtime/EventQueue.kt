package dev.easyide.extwasm.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bounded per-instance event queue (wasm-host.md sec 11.1). Events that only report the
 * latest state (`workspace.didChange`, `editor.didChangeSelection`) are coalesced per path
 * while queued, so a fast typist never fills it. When it is still full the oldest
 * non-coalescible event is dropped and the count rides on the next delivered event.
 */
internal class EventQueue(private val capacity: Int) {

    private class Pending(val event: String, var data: JsonObject) {
        val coalesceKey: String? = if (event in COALESCED) event + "\u0000" + pathOf(data) else null
    }

    private val queue = ArrayDeque<Pending>()
    private var dropped = 0

    @Synchronized
    fun offer(event: String, data: JsonObject) {
        val incoming = Pending(event, data)
        incoming.coalesceKey?.let { key ->
            queue.firstOrNull { it.coalesceKey == key }?.let { it.data = data; return }
        }
        if (queue.size >= capacity) {
            val victim = queue.indexOfFirst { it.coalesceKey == null }.takeIf { it >= 0 } ?: 0
            queue.removeAt(victim)
            dropped++
        }
        queue.addLast(incoming)
    }

    /** Next event as its ABI message, with the drop count since the last one. */
    @Synchronized
    fun poll(): JsonObject? {
        val next = queue.removeFirstOrNull() ?: return null
        val msg = Messages.event(next.event, next.data, dropped)
        dropped = 0
        return msg
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun clear() {
        queue.clear()
        dropped = 0
    }

    private companion object {
        val COALESCED = setOf("workspace.didChange", "editor.didChangeSelection")

        fun pathOf(data: JsonObject): String = (data["path"] as? JsonPrimitive)?.content ?: ""
    }
}

/**
 * Crash-loop detector (wasm-host.md sec 10): true once [maxCrashes] counted failures fall
 * within [windowMs]. Memory only; the resulting disable is persisted by the caller.
 */
internal class CrashWindow(private val maxCrashes: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun record(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        return times.size >= maxCrashes
    }

    @Synchronized
    fun reset() = times.clear()
}
