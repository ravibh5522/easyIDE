package dev.easyide.app.extensions.registry

/**
 * Internal registry constants, one declarative table (registry-and-install.md sec 15). None
 * is a settings key; the package size cap is `extensions.limits.packageMb`.
 */
object RegistryPolicy {
    /** `index.json` and `revocations.json`, each. */
    const val MAX_INDEX_BYTES = 8L * 1024 * 1024

    /** A `.sig` document: `{keyId, alg, sig}` is well under 1 KiB. */
    const val MAX_SIG_BYTES = 4L * 1024

    /** One `publishers/<p>.json`. */
    const val MAX_PUBLISHER_BYTES = 256L * 1024

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000

    /** Versions kept beside `current` (the one rollback returns to). */
    const val RETAINED_VERSIONS = 1

    /** Package cache budget; packages referenced by an install are never evicted (sec 12). */
    const val CACHE_MAX_MB = 512L

    /** Past this age browse and install sheets warn that the index may be stale (R6). */
    const val STALE_INDEX_WARN_DAYS = 14L

    const val DAILY_MS = 24L * 60 * 60 * 1000
    const val WEEKLY_MS = 7 * DAILY_MS

    /** The only scheme a registry URL, a package URL or a redirect may use. */
    const val SCHEME = "https://"
}
