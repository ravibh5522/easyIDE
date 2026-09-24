package dev.easyide.app.extensions.registry

import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.Verified
import dev.easyide.sandbox.SandboxPaths
import java.io.File
import java.io.IOException

/**
 * The offline package cache `extensions/cache/<sha256>.easyext` (registry-and-install.md sec
 * 8.2 step 2, sec 12). Content-addressed and never trusted: every use re-checks the bytes
 * against the signed entry, a bad cached file is deleted and fetched again, and a download
 * becomes `<sha256>.easyext` only after its check passed (`.partial` until then).
 */
class PackageCache(private val paths: SandboxPaths, private val http: HttpFetcher) {

    fun file(sha256: String): File = File(paths.extensionPackageCacheDir, "$sha256$EXT")

    /** A cached file of the right size exists (its hash is checked on use). */
    fun has(entry: IndexEntry): Boolean = file(entry.sha256).let { it.isFile && it.length() == entry.size }

    sealed interface Obtained {
        /** Checked bytes: exactly what gets unpacked, so nothing can swap them after the check. */
        data class Ok(val bytes: ByteArray, val fromCache: Boolean) : Obtained
        data class Failed(val error: RegistryError) : Obtained
    }

    /**
     * The package of [entry], from the cache or downloaded, passing [check] (size and sha256
     * against the signed entry). [maxBytes] is `extensions.limits.packageMb`.
     */
    fun obtain(entry: IndexEntry, maxBytes: Long, check: (ByteArray) -> Verified<Unit>): Obtained {
        if (entry.size > maxBytes) {
            return Obtained.Failed(RegistryError.Rejected("${entry.id} ${entry.version}: ${entry.size} bytes exceeds the package limit of $maxBytes"))
        }
        val cached = file(entry.sha256)
        if (cached.isFile) {
            val bytes = try { cached.readBytes() } catch (e: IOException) { null }
            if (bytes != null && check(bytes) is Verified.Ok) {
                cached.setLastModified(System.currentTimeMillis()) // LRU
                return Obtained.Ok(bytes, fromCache = true)
            }
            cached.delete() // corrupt or tampered cache entry: never used, fetched again
        }
        val partial = File(paths.extensionPackageCacheDir, "${entry.sha256}$PARTIAL")
        return try {
            paths.extensionPackageCacheDir.mkdirs()
            val r = try {
                // One byte over: a longer body reaches checkBytes and fails as an integrity error, not a network one.
                http.get(entry.url, null, entry.size + 1, partial)
            } catch (e: IOException) {
                FetchResult.Failed("${entry.url}: ${e.message ?: e.javaClass.simpleName}")
            }
            when (r) {
                is FetchResult.Failed -> Obtained.Failed(RegistryError.Network(r.reason))
                FetchResult.NotModified -> Obtained.Failed(RegistryError.Network("${entry.url}: unexpected 304"))
                is FetchResult.Fetched -> {
                    val bytes = partial.readBytes()
                    when (val v = check(bytes)) {
                        // Registry packages: a mismatch is a hard stop with no "install anyway" (arch.md sec 9).
                        is Verified.Rejected -> Obtained.Failed(RegistryError.Rejected(v.reason))
                        is Verified.Ok -> {
                            replace(partial, cached)
                            Obtained.Ok(bytes, fromCache = false)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Obtained.Failed(RegistryError.Storage("${entry.id}: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            partial.delete()
        }
    }

    /**
     * Evicts least recently used packages until the cache fits [RegistryPolicy.CACHE_MAX_MB];
     * [pinned] (sha256 of every installed current and previous version) are never evicted.
     */
    fun gc(pinned: Set<String>) {
        val files = paths.extensionPackageCacheDir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val budget = RegistryPolicy.CACHE_MAX_MB * 1024 * 1024
        for (f in files) {
            if (total <= budget) break
            if (f.name.removeSuffix(EXT) in pinned) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private companion object {
        const val EXT = ".easyext"
        const val PARTIAL = ".partial"
    }
}
