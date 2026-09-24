package dev.easyide.lsp.diagnostics

import dev.easyide.lsp.protocol.Diagnostic
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One server's diagnostics for one document. [version] is the document version they were
 * computed for when the server said so (push `version`, or the version a pull was sent at);
 * presenters shift or drop older sets (lsp-features.md 3.2 ShiftAdjusted).
 */
data class DiagnosticSet(val version: Int?, val items: List<Diagnostic>)

/**
 * Diagnostics of one (environment, project) from every server, keyed by canonical uri then
 * server - the `DiagnosticsSink` of hld.md. Kept for closed files too (Problems panel);
 * a server's entries are dropped when it stops.
 */
class DiagnosticStore {
    private val state = MutableStateFlow<Map<String, Map<ServerKey, DiagnosticSet>>>(emptyMap())

    val byUri: StateFlow<Map<String, Map<ServerKey, DiagnosticSet>>> = state.asStateFlow()

    /** Replaces [key]'s set for [uri]; an empty list clears it. */
    fun put(uri: String, key: ServerKey, version: Int?, items: List<Diagnostic>) {
        val u = FileUri.canonical(uri)
        state.update { all ->
            val forUri = all[u].orEmpty()
            val next = if (items.isEmpty()) forUri - key else forUri + (key to DiagnosticSet(version, items))
            if (next.isEmpty()) all - u else all + (u to next)
        }
    }

    fun clearServer(key: ServerKey) {
        state.update { all ->
            all.mapValues { (_, m) -> m - key }.filterValues { it.isNotEmpty() }
        }
    }
}
