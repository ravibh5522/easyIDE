package dev.easyide.app.ui.screens.workspace.session

import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogSink
import dev.easyide.app.diagnostics.LogSource
import dev.easyide.app.session.BackupRef
import dev.easyide.app.session.Hashes
import dev.easyide.app.session.SessionSnapshot
import dev.easyide.app.session.SessionStore
import dev.easyide.app.session.TabSnapshot
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.WorkspaceLayoutHolder
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes one workspace's [SessionSnapshot] and its unsaved buffers through [SessionStore].
 *
 * Every state that is worth restoring is read here from where it already lives (tabs and
 * explorer folders in the UI state, carets in [EditorSelections], offsets in [EditorScrolls],
 * stages in [WorkspaceLayoutHolder]); nothing is mirrored, so the snapshot cannot drift from
 * what the user sees.
 *
 * Writes are serialised by a lock and stop for good once [discard] ran: an explicit close
 * must not be undone by a save that was already scheduled.
 */
internal class SessionPersistence(
    private val projectId: String,
    private val state: MutableStateFlow<WorkspaceUiState>,
    private val selections: EditorSelections,
    private val scrolls: EditorScrolls,
    private val layout: WorkspaceLayoutHolder,
    private val store: SessionStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: LogSink,
) {
    private val lock = Any()
    private var closed = false

    /** Hash of the text each backup file currently holds, so an unchanged buffer is not rewritten. */
    private val written = HashMap<String, String>()

    /** Hashes by string identity: an unchanged buffer is the same instance, so it is hashed once. */
    private val hashed = HashMap<String, Pair<String, String>>()

    suspend fun save() = withContext(io) {
        synchronized(lock) { if (!closed) write(snapshot()) }
    }

    /** Ends persistence and deletes what was stored. Blocks only for a write already in flight. */
    fun discard() = synchronized(lock) {
        closed = true
        try {
            store.clear(projectId)
        } catch (e: IOException) {
            log.log(LogLevel.WARN, LogSource.APP, "could not delete the stored session of $projectId: ${e.message}")
        }
    }

    /** Stops persisting without deleting anything: the session ended but should be recoverable. */
    fun stop() = synchronized(lock) { closed = true }

    private fun write(built: Built) {
        try {
            store.save(built.snapshot, built.backups.filter { (name, text) -> written[name] != sha(name, text) })
            built.backups.forEach { (name, text) -> written[name] = sha(name, text) }
            // A backup the store just pruned must be rewritten if that buffer turns dirty again with the same text.
            written.keys.retainAll(built.backups.keys)
            hashed.keys.retainAll(built.liveKeys)
        } catch (e: IOException) {
            // Disk full or unwritable: the next change retries; nothing the user did depends on this write.
            log.log(LogLevel.ERROR, LogSource.APP, "could not save the session of $projectId: ${e.message}")
        }
    }

    private class Built(val snapshot: SessionSnapshot, val backups: Map<String, String>, val liveKeys: Set<String>)

    private fun snapshot(): Built {
        val s = state.value
        val backups = HashMap<String, String>()
        val liveKeys = HashSet<String>()
        val tabs = s.openTabs.map { tab ->
            val backup = if (tab.isDirty) backupOf(tab, backups, liveKeys) else null
            val caret = selections[tab.relativePath]
            val scroll = scrolls[tab.relativePath]
            TabSnapshot(tab.relativePath, caret.start, caret.end, scroll.y, scroll.x, tab.showPreview, backup)
        }
        val snapshot = SessionSnapshot(
            projectId = projectId,
            savedAtMs = clock(),
            tabs = tabs,
            activePath = s.activeTabPath,
            expandedDirs = s.expandedDirs.sorted(),
            layout = layout.snapshot(),
        )
        return Built(snapshot, backups, liveKeys)
    }

    private fun backupOf(tab: EditorTab, backups: MutableMap<String, String>, liveKeys: MutableSet<String>): BackupRef {
        val file = SessionStore.backupNameFor(tab.relativePath)
        val baseKey = tab.relativePath + BASE_KEY
        backups[file] = tab.content
        liveKeys += file
        liveKeys += baseKey
        return BackupRef(file, sha(baseKey, tab.savedContent))
    }

    private fun sha(key: String, text: String): String {
        val cached = hashed[key]
        if (cached != null && cached.first === text) return cached.second
        return Hashes.sha256Hex(text).also { hashed[key] = text to it }
    }

    private companion object {
        /** Distinguishes the cache slot of a tab's saved text from its buffer's. */
        const val BASE_KEY = "#base"
    }
}
