package dev.easyide.lsp.client

import dev.easyide.lsp.client.FeatureRouting.quietly
import dev.easyide.lsp.protocol.LspMethods
import dev.easyide.lsp.protocol.LspParams
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SemanticToken
import dev.easyide.lsp.protocol.SemanticTokensCodec
import dev.easyide.lsp.protocol.SemanticTokensDeltaResult
import dev.easyide.lsp.protocol.ServerCapabilities
import dev.easyide.lsp.protocol.documentParams
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.workspace.FileUri
import java.util.concurrent.ConcurrentHashMap

/** Decoded semantic tokens: absolute line, UTF-16 column, length, type and modifier names. */
data class SemanticTokensResult(val tokens: List<SemanticToken>)

/**
 * Keeps the last full token stream per (server, document) so later requests can be
 * `full/delta` (lsp-features.md 4.13). An entry is tied to the [ServerCapabilities] instance
 * it came from: after a restart the new process knows no old `resultId`, so the entry is
 * ignored and a full request is made.
 */
internal class SemanticTokensCache {
    private class Entry(val capabilities: ServerCapabilities, val resultId: String, val data: IntArray)

    private val entries = ConcurrentHashMap<Pair<ServerKey, String>, Entry>()

    suspend fun full(session: LspSession, uri: String): FromServer<SemanticTokensResult>? {
        val caps = session.capabilities.value ?: return null
        val legend = caps.semanticTokensLegend ?: return null
        val key = session.key to FileUri.canonical(uri)
        val previous = entries[key]?.takeIf { it.capabilities === caps }
        if (previous != null && caps.semanticTokensDelta) {
            val r = quietly(session, LspMethods.SEMANTIC_TOKENS_DELTA, LspParams.semanticTokensDelta(uri, previous.resultId), uri)
            val data = when (val v = r?.value) {
                is SemanticTokensDeltaResult.Delta -> SemanticTokensCodec.applyEdits(previous.data, v.edits)?.also { remember(key, caps, v.resultId, it) }
                is SemanticTokensDeltaResult.Full -> v.tokens.data.also { remember(key, caps, v.tokens.resultId, it) }
                null -> null
            }
            // A delta that does not apply falls through to a full request.
            if (r != null && data != null) return FromServer(r.server, SemanticTokensResult(SemanticTokensCodec.decode(data, legend)), r.version)
        }
        if (!caps.semanticTokensFull) return null
        val r = quietly(session, LspMethods.SEMANTIC_TOKENS_FULL, documentParams(uri), uri) ?: return null
        val tokens = r.value ?: return null
        remember(key, caps, tokens.resultId, tokens.data)
        return FromServer(r.server, SemanticTokensResult(SemanticTokensCodec.decode(tokens.data, legend)), r.version)
    }

    suspend fun range(session: LspSession, uri: String, range: Range): FromServer<SemanticTokensResult>? {
        val caps = session.capabilities.value ?: return null
        val legend = caps.semanticTokensLegend ?: return null
        if (!caps.semanticTokensRange) return null
        val r = quietly(session, LspMethods.SEMANTIC_TOKENS_RANGE, LspParams.range(uri, range), uri) ?: return null
        val tokens = r.value ?: return null
        return FromServer(r.server, SemanticTokensResult(SemanticTokensCodec.decode(tokens.data, legend)), r.version)
    }

    fun forget(uri: String) {
        entries.keys.removeAll { it.second == uri }
    }

    private fun remember(key: Pair<ServerKey, String>, caps: ServerCapabilities, resultId: String?, data: IntArray) {
        if (resultId == null) entries.remove(key) else entries[key] = Entry(caps, resultId, data)
    }
}
