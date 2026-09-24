package dev.easyide.lsp.docs

import dev.easyide.lsp.text.LineIndex
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * One immutable version of an open document. [version] increases on every text change and is
 * the LSP version every session sends; [saveCount] increases on every save so sessions can
 * notice a save even when the text did not change. [openId] is unique per open: versions
 * restart at 1 after a close, so a session that only sees the latest state (flows conflate)
 * tells "closed and reopened" from "edited" by the id, not the version.
 */
class DocSnapshot(
    val uri: String,
    val openId: Long,
    val languageId: String,
    val version: Int,
    val text: String,
    val saveCount: Int,
) {
    val lines: LineIndex by lazy { LineIndex(text) }

    override fun toString(): String = "DocSnapshot($uri, $languageId, v$version, ${text.length} chars, saves=$saveCount)"
}

/**
 * The open documents of one (environment, project), shared by all its sessions. The editor
 * bridge writes it; sessions reconcile against [snapshots] instead of consuming events, so a
 * session that starts late or misses a conflated update still converges to the right state.
 *
 * URIs are stored canonical ([FileUri.canonical]) so a server's spelling of a URI finds the
 * same entry. Thread-safe: every mutation is one atomic [update].
 */
class DocumentStore {
    private val docs = MutableStateFlow<Map<String, DocSnapshot>>(emptyMap())
    private val openIds = AtomicLong()

    val snapshots: StateFlow<Map<String, DocSnapshot>> = docs.asStateFlow()

    fun snapshot(uri: String): DocSnapshot? = docs.value[FileUri.canonical(uri)]

    /** Opens (version 1), or reopens an already open uri as its next version. */
    fun open(uri: String, languageId: String, text: String) {
        val key = FileUri.canonical(uri)
        val fresh = openIds.incrementAndGet()
        docs.update { m ->
            val old = m[key]
            m + (key to DocSnapshot(key, old?.openId ?: fresh, languageId, (old?.version ?: 0) + 1, text, old?.saveCount ?: 0))
        }
    }

    /** New text; the version moves only when the content differs. Unknown uris are ignored. */
    fun update(uri: String, text: String) {
        val key = FileUri.canonical(uri)
        docs.update { m ->
            val old = m[key] ?: return@update m
            if (old.text == text) m else m + (key to DocSnapshot(key, old.openId, old.languageId, old.version + 1, text, old.saveCount))
        }
    }

    /** A successful save of [text] (which may differ from the buffer if the save formatted it). */
    fun saved(uri: String, text: String) {
        val key = FileUri.canonical(uri)
        docs.update { m ->
            val old = m[key] ?: return@update m
            val version = if (old.text == text) old.version else old.version + 1
            m + (key to DocSnapshot(key, old.openId, old.languageId, version, text, old.saveCount + 1))
        }
    }

    fun close(uri: String) {
        val key = FileUri.canonical(uri)
        docs.update { it - key }
    }

    fun closeAll() {
        docs.value = emptyMap()
    }
}
