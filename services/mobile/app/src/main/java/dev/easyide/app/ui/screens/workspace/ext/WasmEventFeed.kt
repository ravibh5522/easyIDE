package dev.easyide.app.ui.screens.workspace.ext

import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.extensions.action.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** One open tab as the event diff sees it; [content] and [saved] are compared by identity first. */
data class TabSnapshot(val path: String, val languageId: String?, val content: String, val saved: String)

/**
 * WASM workspace events from tab state (sdk-reference `events.subscribe` names): a tab that
 * appears is `workspace.didOpen`, one that goes is `didClose`, a new buffer is `didChange`
 * (with a version that rises per change), and a new saved text equal to the buffer is
 * `didSave`. Pure over two snapshots, so the rules are unit tests.
 */
class WorkspaceEventDiff {
    private val versions = ConcurrentHashMap<String, Int>()

    /** Buffer version of guest [path] (1 on open, +1 per change); 0 when not open. */
    fun version(path: String): Int = versions[path] ?: 0

    fun diff(before: Map<String, TabSnapshot>, after: Map<String, TabSnapshot>): List<Pair<String, JsonObject>> {
        val out = ArrayList<Pair<String, JsonObject>>()
        for ((path, tab) in after) {
            val old = before[path]
            when {
                old == null -> {
                    versions[path] = 1
                    out += DID_OPEN to buildJsonObject { put(PATH, path); put(LANGUAGE_ID, tab.languageId); put(VERSION, 1) }
                }
                old.content !== tab.content && old.content != tab.content -> {
                    val v = versions.merge(path, 1) { a, b -> a + b } ?: 1
                    out += DID_CHANGE to buildJsonObject { put(PATH, path); put(VERSION, v) }
                }
            }
            if (old != null && old.saved !== tab.saved && tab.saved == tab.content) out += DID_SAVE to buildJsonObject { put(PATH, path) }
        }
        for (path in before.keys - after.keys) {
            versions.remove(path)
            out += DID_CLOSE to buildJsonObject { put(PATH, path) }
        }
        return out
    }

    companion object {
        const val DID_OPEN = "workspace.didOpen"
        const val DID_CHANGE = "workspace.didChange"
        const val DID_SAVE = "workspace.didSave"
        const val DID_CLOSE = "workspace.didClose"
        const val DID_CHANGE_SELECTION = "editor.didChangeSelection"
        private const val PATH = "path"
        private const val LANGUAGE_ID = "languageId"
        private const val VERSION = "version"
    }
}

/**
 * Feeds one workspace's tab and caret changes to the WASM host ([post]), off the main
 * thread. Started and stopped with the workspace's attachment to the extension host.
 */
class WasmEventFeed(
    private val state: StateFlow<WorkspaceUiState>,
    private val selections: EditorSelections,
    private val languageOf: (String) -> String?,
    private val selectionRange: (text: String, start: Int, end: Int) -> JsonObject,
    private val post: (String, JsonObject) -> Unit,
    private val scope: CoroutineScope,
) {
    val diff = WorkspaceEventDiff()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            var tabs = emptyMap<String, TabSnapshot>()
            var caret: Pair<String, Pair<Int, Int>>? = null
            combine(state, selections.changes) { s, _ -> s }.collect { s ->
                val next = s.openTabs.filter { it.editable }.associate { t ->
                    val path = GUEST + t.relativePath
                    path to TabSnapshot(path, languageOf(t.relativePath), t.content, t.savedContent)
                }
                diff.diff(tabs, next).forEach { (event, data) -> post(event, data) }
                tabs = next
                val active = s.activeTab?.takeIf { it.editable } ?: return@collect
                val sel = selections[active.relativePath]
                val now = (GUEST + active.relativePath) to (sel.min to sel.max)
                if (caret != null && caret != now) {
                    post(WorkspaceEventDiff.DID_CHANGE_SELECTION, buildJsonObject {
                        put("path", now.first)
                        put("selections", JsonArray(listOf(selectionRange(active.content, sel.min, sel.max))))
                    })
                }
                caret = now
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val GUEST = WorkspaceState.GUEST_WORKSPACE + "/"
    }
}
