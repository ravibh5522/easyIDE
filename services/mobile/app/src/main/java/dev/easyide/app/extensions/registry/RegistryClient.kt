package dev.easyide.app.extensions.registry

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.registry.PublisherKeys
import dev.easyide.extensions.registry.RegistryVerifier
import dev.easyide.extensions.registry.Verified
import dev.easyide.extensions.registry.VerifiedIndex
import dev.easyide.sandbox.SandboxPaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Why registry data could not be fetched or used; every one is shown with its reason. */
sealed interface RegistryError {
    val reason: String

    /** The network boundary: the last verified copy stays in use. */
    data class Network(override val reason: String) : RegistryError

    /** Signature, shape or anti-rollback failure: a hard stop, never "use anyway". */
    data class Rejected(override val reason: String) : RegistryError

    data class Storage(override val reason: String) : RegistryError
}

sealed interface RegistryResult<out T> {
    data class Ok<T>(val value: T) : RegistryResult<T>
    data class Failed(val error: RegistryError) : RegistryResult<Nothing>
}

/** A verified index as last fetched; [fetchedAt] is the last successful refresh (index age). */
class CachedIndex(val index: VerifiedIndex, val fetchedAt: Instant)

/**
 * Fetches, verifies and caches registry indexes (registry-and-install.md sec 3, 11.1):
 * `revocations.json` and `index.json` with their `.sig`s go to a staging dir with
 * If-None-Match, are verified with anti-rollback against the last verified copy, and only
 * then replace `extensions/registry/<id>/` (rename aside, rename in). Any failure leaves the
 * previous copy, which works offline. Publisher files are fetched lazily and verified before
 * they are stored.
 *
 * The disk copy is not trusted either: [cached] verifies it again on first read, so a
 * [CachedIndex] always wraps a [VerifiedIndex]. Blocking; call on an IO dispatcher.
 */
class RegistryClient(
    private val paths: SandboxPaths,
    private val http: HttpFetcher,
    private val verifier: RegistryVerifier,
    private val clock: () -> Instant = Instant::now,
) {
    private val memo = ConcurrentHashMap<String, Pair<RegistryConfig, CachedIndex>>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** The last verified copy of [config]'s index, or null when there is none (or it no longer verifies). */
    fun cached(config: RegistryConfig): CachedIndex? = synchronized(lock(config.id)) { loadCached(config) }

    fun refresh(config: RegistryConfig): RegistryResult<CachedIndex> = synchronized(lock(config.id)) {
        val dir = paths.extensionRegistryDir(config.id)
        val previous = loadCached(config)
        val meta = Meta.read(dir)
        val staging = File(paths.extensionStagingDir, "registry-${config.id}-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            val etags = HashMap<String, String>()
            for (name in INDEX_FILES) {
                val cachedTag = meta.etags[name]?.takeIf { previous != null && File(dir, name).isFile }
                when (val r = fetch(config.fileUrl(name), cachedTag, cap(name), File(staging, name), File(dir, name))) {
                    is Fetch.Ok -> r.etag?.let { etags[name] = it }
                    is Fetch.Failed -> return RegistryResult.Failed(RegistryError.Network(r.reason))
                }
            }
            fun b(name: String) = File(staging, name).readBytes()
            val verified = verifier.verifyIndex(
                config.id, config.rootKey, b(INDEX), b(INDEX + SIG), b(REVOCATIONS), b(REVOCATIONS + SIG), previous?.index,
            )
            val index = when (verified) {
                is Verified.Rejected -> return RegistryResult.Failed(RegistryError.Rejected("${config.id}: ${verified.reason}"))
                is Verified.Ok -> verified.value
            }
            // Publisher files stay: they are verified again on every use.
            File(dir, PUBLISHERS).takeIf { it.isDirectory }?.copyRecursively(File(staging, PUBLISHERS), overwrite = true)
            meta.etags.filterKeys { it.startsWith("$PUBLISHERS/") }.forEach { (k, v) -> etags.putIfAbsent(k, v) }
            val fetchedAt = clock()
            Meta(fetchedAt, etags).write(staging)
            swap(staging, dir)
            CachedIndex(index, fetchedAt).also { memo[config.id] = config to it }.let { RegistryResult.Ok(it) }
        } catch (e: IOException) {
            RegistryResult.Failed(RegistryError.Storage("${config.id}: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * `publishers/<publisher>.json` verified against the root key. With [allowNetwork] it is
     * fetched (If-None-Match) first; a network failure falls back to the stored copy, a
     * verification failure does not (hard stop). Only verified files are stored.
     */
    fun publisherKeys(config: RegistryConfig, publisher: String, allowNetwork: Boolean): RegistryResult<PublisherKeys> = synchronized(lock(config.id)) {
        val dir = paths.extensionRegistryDir(config.id)
        if (!dir.isDirectory) return RegistryResult.Failed(RegistryError.Network("${config.id}: no verified index yet; refresh first"))
        val name = "$PUBLISHERS/$publisher.json"
        var networkFailure: String? = null
        if (allowNetwork) {
            val staging = File(paths.extensionStagingDir, "publisher-${config.id}-${UUID.randomUUID()}")
            try {
                staging.mkdirs()
                val meta = Meta.read(dir)
                val tags = HashMap<String, String>()
                for (f in listOf(name, name + SIG)) {
                    val target = File(staging, f.substringAfter('/'))
                    val tag = meta.etags[f]?.takeIf { File(dir, f).isFile }
                    when (val r = fetch(config.fileUrl(f), tag, cap(f), target, File(dir, f))) {
                        is Fetch.Ok -> r.etag?.let { tags[f] = it }
                        is Fetch.Failed -> { networkFailure = r.reason; break }
                    }
                }
                if (networkFailure == null) {
                    val json = File(staging, "$publisher.json")
                    val sig = File(staging, "$publisher.json$SIG")
                    return when (val v = verifier.verifyPublisher(config.rootKey, json.readBytes(), sig.readBytes(), publisher)) {
                        is Verified.Rejected -> RegistryResult.Failed(RegistryError.Rejected("${config.id}: ${v.reason}"))
                        is Verified.Ok -> {
                            File(dir, PUBLISHERS).mkdirs()
                            replace(json, File(dir, name))
                            replace(sig, File(dir, name + SIG))
                            val meta2 = Meta.read(dir)
                            Meta(meta2.fetchedAt, meta2.etags - name - (name + SIG) + tags).write(dir)
                            RegistryResult.Ok(v.value)
                        }
                    }
                }
            } catch (e: IOException) {
                return RegistryResult.Failed(RegistryError.Storage("${config.id}: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                staging.deleteRecursively()
            }
        }
        val json = File(dir, name)
        val sig = File(dir, name + SIG)
        if (!json.isFile || !sig.isFile) {
            return RegistryResult.Failed(RegistryError.Network(networkFailure ?: "${config.id}: publisher '$publisher' is not cached"))
        }
        return try {
            when (val v = verifier.verifyPublisher(config.rootKey, json.readBytes(), sig.readBytes(), publisher)) {
                is Verified.Rejected -> RegistryResult.Failed(RegistryError.Rejected("${config.id}: ${v.reason}"))
                is Verified.Ok -> RegistryResult.Ok(v.value)
            }
        } catch (e: IOException) {
            RegistryResult.Failed(RegistryError.Storage("${config.id}: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun loadCached(config: RegistryConfig): CachedIndex? {
        memo[config.id]?.let { (c, idx) -> if (c == config) return idx }
        val dir = paths.extensionRegistryDir(config.id)
        recover(dir)
        val files = INDEX_FILES.map { File(dir, it) }
        if (files.any { !it.isFile }) return null
        val bytes = try { files.map { it.readBytes() } } catch (e: IOException) { return null }
        val index = (verifier.verifyIndex(config.id, config.rootKey, bytes[2], bytes[3], bytes[0], bytes[1], null) as? Verified.Ok)?.value
            ?: return null
        val fetchedAt = Meta.read(dir).fetchedAt ?: Instant.EPOCH
        return CachedIndex(index, fetchedAt).also { memo[config.id] = config to it }
    }

    private sealed interface Fetch {
        data class Ok(val etag: String?) : Fetch
        data class Failed(val reason: String) : Fetch
    }

    /** One GET into [into]; a 304 copies [cached] there. An [IOException] from the port is a network failure too. */
    private fun fetch(url: String, etag: String?, maxBytes: Long, into: File, cached: File): Fetch {
        val r = try {
            http.get(url, etag, maxBytes, into)
        } catch (e: IOException) {
            FetchResult.Failed("$url: ${e.message ?: e.javaClass.simpleName}")
        }
        return when (r) {
            is FetchResult.Fetched -> Fetch.Ok(r.etag)
            FetchResult.NotModified -> if (etag != null && cached.isFile) {
                cached.copyTo(into, overwrite = true)
                Fetch.Ok(etag)
            } else {
                Fetch.Failed("$url: 304 without a cached copy")
            }
            is FetchResult.Failed -> Fetch.Failed(r.reason)
        }
    }

    /** Rename the old copy aside, rename the new one in, then drop the old one. */
    private fun swap(staging: File, dir: File) {
        val aside = File(dir.parentFile, dir.name + ASIDE)
        aside.deleteRecursively()
        dir.parentFile?.mkdirs()
        if (dir.exists()) move(dir, aside)
        move(staging, dir)
        aside.deleteRecursively()
    }

    /** A crash between the two renames of [swap] leaves only the aside copy: put it back. */
    private fun recover(dir: File) {
        val aside = File(dir.parentFile, dir.name + ASIDE)
        if (!dir.exists() && aside.isDirectory) {
            try { move(aside, dir) } catch (e: IOException) { /* stays missing: the next refresh rebuilds it */ }
        }
    }

    private fun lock(id: String): Any = locks.getOrPut(id) { Any() }

    private fun cap(name: String): Long = when {
        name.endsWith(SIG) -> RegistryPolicy.MAX_SIG_BYTES
        name.startsWith("$PUBLISHERS/") -> RegistryPolicy.MAX_PUBLISHER_BYTES
        else -> RegistryPolicy.MAX_INDEX_BYTES
    }

    /** `meta.json`: when the last refresh succeeded and the ETags of what it stored. */
    private data class Meta(val fetchedAt: Instant?, val etags: Map<String, String>) {
        fun write(dir: File) {
            val json = JsonObject(mapOf(
                "fetchedAt" to (fetchedAt?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive("")),
                "etags" to JsonObject(etags.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            ))
            val tmp = File(dir, "$META.tmp")
            FileOutputStream(tmp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
            replace(tmp, File(dir, META))
        }

        companion object {
            fun read(dir: File): Meta {
                val text = try { File(dir, META).takeIf { it.isFile }?.readText() } catch (e: IOException) { null } ?: return Meta(null, emptyMap())
                val o = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject ?: return Meta(null, emptyMap())
                val at = o["fetchedAt"]?.stringOrNull?.let { try { Instant.parse(it) } catch (e: DateTimeParseException) { null } }
                val tags = (o["etags"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }.toMap()
                return Meta(at, tags)
            }
        }
    }

    companion object {
        const val INDEX = "index.json"
        const val REVOCATIONS = "revocations.json"
        const val SIG = ".sig"
        const val PUBLISHERS = "publishers"
        const val META = "meta.json"
        private const val ASIDE = ".old"

        /** Fetch order of sec 3.1: revocations first, so an index is never used without them. */
        val INDEX_FILES = listOf(REVOCATIONS, REVOCATIONS + SIG, INDEX, INDEX + SIG)
    }
}

/** Same filesystem by construction (all under filesDir), so a rename. */
internal fun move(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath())
    }
}

internal fun replace(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
