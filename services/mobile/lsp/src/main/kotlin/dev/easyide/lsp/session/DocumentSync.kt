package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.docs.DocSnapshot
import dev.easyide.lsp.json.putOpt
import dev.easyide.lsp.protocol.LanguageIds
import dev.easyide.lsp.protocol.SyncKind
import dev.easyide.lsp.protocol.SyncOptions
import dev.easyide.lsp.protocol.TextDocumentIdentifier
import dev.easyide.lsp.text.EditDelta
import dev.easyide.lsp.text.TextDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A change the server now has; [delta] is null for Full sync (no diff computed). */
data class SyncedChange(val uri: String, val version: Int, val delta: EditDelta?)

/**
 * Document sync for one server process (lsp-client.md sec 5): reconciles the shared
 * [dev.easyide.lsp.docs.DocumentStore] snapshot into `didOpen` / `didChange` / `didSave` /
 * `didClose` for this server.
 *
 * Confined to the session dispatcher. Every notification and request of the session goes
 * through the same writer in call order, so per uri the server sees
 * didOpen < didChange* < didSave < didClose.
 *
 * @param eligible whether a document belongs to this server (its language).
 */
internal class DocumentSync(
    private val options: SyncOptions,
    private val notify: (method: String, params: JsonElement) -> Unit,
    private val eligible: (DocSnapshot) -> Boolean,
) {
    /** What the server has for one uri: the exact snapshot last sent (shared, not copied). */
    private class Synced(var sent: DocSnapshot, var dirty: Boolean)

    private val synced = HashMap<String, Synced>()
    private val tooLarge = HashSet<String>()
    private var latest: Map<String, DocSnapshot> = emptyMap()

    /** Tracks text at all: kind NONE with no open/close means the server wants nothing. */
    private val tracks = options.openClose || options.change != SyncKind.NONE

    /** Eligible open documents, whether or not the sync kind lets us send them. */
    var eligibleCount: Int = 0
        private set

    /** Uris not synced because they exceed [LspPolicy.MAX_FULL_SYNC_BYTES] for a Full-sync server. */
    val oversized: Set<String> get() = tooLarge

    /**
     * Brings the server in line with [snapshots]. Changed documents are only marked dirty (the
     * caller debounces [flushAll]); opens, closes and saves are sent now.
     *
     * @return changes sent immediately (flushes forced by a save), for delta publishing.
     */
    fun reconcile(snapshots: Map<String, DocSnapshot>): List<SyncedChange> {
        latest = snapshots
        val wanted = snapshots.filterValues(eligible)
        eligibleCount = wanted.size
        tooLarge.retainAll(wanted.keys)
        if (!tracks) return emptyList()

        val gone = synced.filter { (uri, s) -> wanted[uri]?.let { it.openId != s.sent.openId || it.languageId != s.sent.languageId } ?: true }
        gone.keys.forEach(::close)

        val sent = mutableListOf<SyncedChange>()
        for ((uri, snap) in wanted) {
            if (isOversized(snap)) {
                if (synced.containsKey(uri)) close(uri)
                tooLarge += uri
                continue
            }
            tooLarge -= uri
            val s = synced[uri]
            if (s == null) {
                open(snap)
                continue
            }
            if (snap.version > s.sent.version) s.dirty = true
            if (snap.saveCount != s.sent.saveCount) {
                flush(uri)?.let(sent::add)
                save(uri, snap)
            }
        }
        return sent
    }

    /** Sends the pending change for [uri] now (before a request about it). */
    fun flush(uri: String): SyncedChange? {
        val s = synced[uri]?.takeIf { it.dirty } ?: return null
        val snap = latest[uri] ?: return null
        s.dirty = false
        if (isOversized(snap)) {
            close(uri)
            tooLarge += uri
            return null
        }
        val old = s.sent
        s.sent = snap
        return when (options.change) {
            SyncKind.NONE -> null
            SyncKind.FULL -> {
                sendChange(uri, snap.version, buildJsonObject { put("text", JsonPrimitive(snap.text)) })
                SyncedChange(uri, snap.version, null)
            }
            SyncKind.INCREMENTAL -> {
                val change = TextDiff.compute(old.text, snap.text, old.lines) ?: return null
                sendChange(uri, snap.version, buildJsonObject {
                    put("range", change.range.toJson())
                    put("text", JsonPrimitive(change.text))
                })
                SyncedChange(uri, snap.version, change.delta)
            }
        }
    }

    fun flushAll(): List<SyncedChange> = synced.keys.toList().mapNotNull(::flush)

    /** Version the server has for [uri] after a flush; null when the server does not have it. */
    fun versionOf(uri: String): Int? = synced[uri]?.sent?.version

    fun isSynced(uri: String): Boolean = synced.containsKey(uri)

    fun syncedUris(): Set<String> = synced.keys.toSet()

    /** `didClose` for everything (project released). */
    fun closeAll() {
        synced.keys.toList().forEach(::close)
    }

    private fun isOversized(snap: DocSnapshot): Boolean =
        options.change == SyncKind.FULL &&
            snap.text.length.toLong() * LspPolicy.UTF8_BYTES_PER_UTF16_UNIT > LspPolicy.MAX_FULL_SYNC_BYTES

    private fun open(snap: DocSnapshot) {
        synced[snap.uri] = Synced(snap, dirty = false)
        if (!options.openClose) return
        notify("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", JsonPrimitive(snap.uri))
                put("languageId", JsonPrimitive(LanguageIds.wire(snap.languageId)))
                put("version", JsonPrimitive(snap.version))
                put("text", JsonPrimitive(snap.text))
            })
        })
    }

    private fun close(uri: String) {
        synced.remove(uri) ?: return
        if (options.openClose) notify("textDocument/didClose", buildJsonObject { put("textDocument", TextDocumentIdentifier(uri).toJson()) })
    }

    private fun save(uri: String, snap: DocSnapshot) {
        val s = synced[uri] ?: return
        s.sent = snap
        if (!options.save) return
        notify("textDocument/didSave", buildJsonObject {
            put("textDocument", TextDocumentIdentifier(uri).toJson())
            putOpt("text", if (options.saveIncludesText) snap.text else null)
        })
    }

    private fun sendChange(uri: String, version: Int, change: JsonElement) {
        notify("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", JsonPrimitive(uri))
                put("version", JsonPrimitive(version))
            })
            put("contentChanges", JsonArray(listOf(change)))
        })
    }
}
