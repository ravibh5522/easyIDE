package dev.easyide.sandbox.download

import java.io.File

/**
 * One file to fetch and pin to an exact digest.
 *
 * @param maxBytes hard ceiling on the file's size, enforced against both the
 *   server's declared length and the bytes actually received - a server that
 *   lies about (or omits) `Content-Length` still cannot fill the disk.
 * @param destination where the verified file ends up. Nothing is ever written
 *   there directly: bytes go to a sibling `.part` file that is renamed into
 *   place only after the digest matches, so [destination] existing always
 *   means "complete and verified at the time it was written".
 */
data class DownloadRequest(
    val url: String,
    val sha256: Sha256,
    val maxBytes: Long,
    val destination: File,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }
}

/** What a [VerifiedDownloader] flow emits, in order: Started, Progress*, Verified. */
sealed interface DownloadEvent {

    /**
     * The transfer began. [resumedFrom] is non-zero when an earlier
     * interrupted attempt's bytes were kept; [totalBytes] is null when the
     * server did not say how large the file is.
     */
    data class Started(val resumedFrom: Long, val totalBytes: Long?) : DownloadEvent

    /** [bytes] counts the whole file so far, including any resumed prefix. */
    data class Progress(val bytes: Long, val totalBytes: Long?) : DownloadEvent

    /**
     * Terminal event: [file] matches the requested digest. [fromCache] is true
     * when it was already on disk and only re-hashed - no network was used.
     */
    data class Verified(val file: File, val bytes: Long, val fromCache: Boolean) : DownloadEvent
}

/**
 * Why a download did not produce a verified file. Whether the `.part` file
 * survives is part of each case's contract, because it decides whether the
 * next attempt resumes or starts over.
 */
sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Non-success HTTP status. Partial bytes are kept: the failure is the server's, not the data's. */
    class HttpStatus(val url: String, val code: Int) :
        DownloadError("GET $url failed with HTTP $code")

    /** Connection or read failure, including a body shorter than its `Content-Length`. Partial bytes are kept for resume. */
    class Network(val url: String, cause: Throwable) :
        DownloadError("Download of $url interrupted: ${cause.message}", cause)

    /** Declared or received size exceeds the limit. Partial bytes are deleted. */
    class TooLarge(val url: String, val limitBytes: Long) :
        DownloadError("Download of $url exceeds the $limitBytes byte limit")

    /**
     * Complete file, wrong digest: corrupted or tampered. Partial bytes are
     * deleted so a retry cannot resume from poisoned data, and there is no way
     * to accept the file anyway.
     */
    class Integrity(val url: String, val expected: Sha256, val actual: Sha256) :
        DownloadError("Download of $url failed integrity check: expected sha256 $expected, got $actual")

    /** Local disk failure (full, permissions, rename). */
    class Storage(val path: String, cause: Throwable?) :
        DownloadError("Storage operation on $path failed" + (cause?.message?.let { ": $it" } ?: ""), cause)
}

/** Transfer tuning shared by every download; one table so no call site inlines a number. */
internal object DownloadPolicy {
    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 30_000
    const val BUFFER_BYTES = 64 * 1024

    /** Progress granularity: fine enough for a smooth bar, coarse enough not to flood collectors. */
    const val PROGRESS_STEP_BYTES = 256L * 1024

    const val PART_SUFFIX = ".part"
}
