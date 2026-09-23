package dev.easyide.lsp.client

import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.LspMethod
import dev.easyide.lsp.session.LspRequestException
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.session.ServerKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

/** The document a feature request is about. [uri] is the guest URI. */
data class DocContext(val environmentId: String, val projectId: String, val uri: String, val languageId: String)

/**
 * A result tagged with the server that produced it, so resolve and command calls go back to
 * the same session (lsp-features.md 1.1), and with the document [version] it was computed for.
 */
data class FromServer<T>(val server: ServerKey, val value: T, val version: Int?)

/**
 * The three routing policies of lsp-features.md 1.1 over the sessions of one language, already
 * ordered by the manager (ready first, priority desc, config order).
 *
 * Merge and first-non-empty treat a server error as an empty answer from that server and
 * record it in its log: one misbehaving server must not hide another's results. Single-owner
 * calls (rename, formatting) propagate [LspRequestException] so the user sees the message.
 */
internal object FeatureRouting {

    /** Queries every supporting session in parallel and keeps each non-null answer. */
    suspend fun <R> merge(
        sessions: List<LspSession>,
        feature: LspFeature,
        method: LspMethod<R>,
        params: JsonElement,
        uri: String?,
    ): List<FromServer<R>> = coroutineScope {
        sessions.filter { it.supports(feature) }
            .map { s -> async { quietly(s, method, params, uri) } }
            .awaitAll()
            .filterNotNull()
    }

    /** Queries in order; the next session is asked only after the previous answered empty. */
    suspend fun <R> firstNonEmpty(
        sessions: List<LspSession>,
        feature: LspFeature,
        method: LspMethod<R>,
        params: JsonElement,
        uri: String?,
        isEmpty: (R) -> Boolean,
    ): FromServer<R>? {
        for (s in sessions.filter { it.supports(feature) }) {
            val r = quietly(s, method, params, uri) ?: continue
            if (!isEmpty(r.value)) return r
        }
        return null
    }

    /**
     * The one session that owns [feature]: [preferredServerId] (`editor.defaultFormatter`)
     * if it supports it, else the first supporting session in manager order.
     */
    fun owner(sessions: List<LspSession>, feature: LspFeature, preferredServerId: String?): LspSession? {
        val supporting = sessions.filter { it.supports(feature) }
        return supporting.firstOrNull { it.key.serverId == preferredServerId } ?: supporting.firstOrNull()
    }

    /** @throws LspRequestException from the owner's error answer. */
    suspend fun <R> single(session: LspSession, method: LspMethod<R>, params: JsonElement, uri: String?): FromServer<R>? {
        val r = session.request(method, params, uri) ?: return null
        return FromServer(session.key, r.value, r.version)
    }

    suspend fun <R> quietly(session: LspSession, method: LspMethod<R>, params: JsonElement, uri: String?): FromServer<R>? =
        try {
            single(session, method, params, uri)
        } catch (e: LspRequestException) {
            session.record("${method.name} failed: ${e.code} ${e.message}")
            null
        }

    /** Concatenates merged lists, dropping items whose [key] was already seen (first server wins). */
    fun <T, K> dedupe(results: List<FromServer<List<T>>>, key: (T) -> K): List<FromServer<T>> {
        val seen = HashSet<K>()
        return results.flatMap { r -> r.value.filter { seen.add(key(it)) }.map { FromServer(r.server, it, r.version) } }
    }
}
