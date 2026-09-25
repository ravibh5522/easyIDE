package dev.easyide.app.ui.shell.ext

import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.view.ViewLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** At most [max] events in any [windowMs]: what keeps one provider from redrawing a view faster than it can be read. */
class UpdateRate(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun allow(now: Long): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }
}

/**
 * The data of every view and document that has been shown, by key (a view id, or a document URI): the one place
 * providers, actions and the user's own input write to, so a view is drawn from one object however it was fed
 * (extension-ui.md section 4.4). Provider updates are limited to [ViewLimits.MAX_UPDATES_PER_SECOND] per key and
 * [ViewLimits.MAX_UPDATE_BYTES] each; local edits (typed text, effects) are the user's and are not limited.
 */
class ViewDataHub(private val clock: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val values: StateFlow<Map<String, JsonObject>> = state.asStateFlow()
    private val rates = ConcurrentHashMap<String, UpdateRate>()

    /** What [key] holds, [initial] (the view's `state`) until anything was written. */
    fun data(key: String, initial: JsonObject): JsonObject = state.value[key] ?: initial

    /** The data of [key] over time, [initial] until something writes; emits only when it changes. */
    fun flow(key: String, initial: JsonObject): Flow<JsonObject> = state.map { it[key] ?: initial }.distinctUntilChanged()

    /** A provider's update: an object merges by top-level key, an array is the `items` of a plain list. False when refused. */
    fun provide(key: String, update: JsonElement, initial: JsonObject = JsonObject(emptyMap())): Boolean {
        val patch = if (update is JsonArray) JsonObject(mapOf("items" to update)) else update
        if (!rates.getOrPut(key) { UpdateRate(ViewLimits.MAX_UPDATES_PER_SECOND, RATE_WINDOW_MS) }.allow(clock())) return false
        return change(key, initial) { ViewData.merge(it, patch) }
    }

    /** A local edit: [edit] applied to the current data. */
    fun edit(key: String, initial: JsonObject, edit: (JsonObject) -> JsonObject) {
        state.update { it + (key to edit(it[key] ?: initial)) }
    }

    /** An action result written where its binding says; false (nothing changed) when the result is refused. */
    fun write(key: String, initial: JsonObject, apply: (JsonObject) -> ViewData.Written): ViewData.Written {
        var out: ViewData.Written = ViewData.Written.Data(initial)
        state.update { all ->
            val written = apply(all[key] ?: initial)
            out = written
            if (written is ViewData.Written.Data) all + (key to written.data) else all
        }
        return out
    }

    private fun change(key: String, initial: JsonObject, apply: (JsonObject) -> ViewData.Written): Boolean = write(key, initial, apply) is ViewData.Written.Data

    /** Forgets [key] (a closed document), so a reopened one starts from its state again. */
    fun drop(key: String) {
        state.update { it - key }
        rates.remove(key)
    }

    private companion object { const val RATE_WINDOW_MS = 1_000L }
}
