package dev.easyide.app.diagnostics

import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.download.Sha256
import dev.easyide.sandbox.model.SandboxImage
import java.io.File
import java.io.IOException

/**
 * How an environment's image relates to the digest we pin for it.
 *
 * [ARCHIVE_REMOVED] is not a failure and is not "verified" either: the rootfs was unpacked
 * from a download that was checked against the pin, but the archive is no longer on disk (it
 * was cleared, or the environment predates the cache), so nothing can be re-hashed today.
 */
enum class ChecksumStatus {
    /** The cached archive exists; it is only hashed when the user asks. */
    NOT_CHECKED,
    VERIFIED,
    MISMATCH,
    ARCHIVE_REMOVED,
    NO_PINNED_ROOTFS,
}

/** The pinned digest of an image for this device and where its archive is cached. */
data class PinnedImage(val digest: Sha256, val archive: File)

object ImageChecksum {
    /**
     * The pinned archive of [image] for [abis], or null when the image ships no rootfs for any
     * of them. The cache file name is derived from the URL exactly as the sandbox runtime does
     * when it downloads ([archiveIdFor]).
     */
    fun pinnedFor(image: SandboxImage, abis: List<String>, paths: SandboxPaths): PinnedImage? {
        val rootfs = image.rootfsFor(abis) ?: return null
        return PinnedImage(rootfs.sha256, paths.cachedImage(archiveIdFor(rootfs.url)))
    }

    /**
     * The status from what is known: [actual] is the archive's digest, null while it has not
     * been hashed (or the archive is absent, see [archivePresent]).
     */
    fun classify(pinned: Sha256?, archivePresent: Boolean, actual: Sha256?): ChecksumStatus = when {
        pinned == null -> ChecksumStatus.NO_PINNED_ROOTFS
        !archivePresent -> ChecksumStatus.ARCHIVE_REMOVED
        actual == null -> ChecksumStatus.NOT_CHECKED
        actual == pinned -> ChecksumStatus.VERIFIED
        else -> ChecksumStatus.MISMATCH
    }

    /** Status without hashing: what is known from the pin and the cache directory alone. */
    fun quickStatus(pinned: PinnedImage?): ChecksumStatus =
        classify(pinned?.digest, pinned?.archive?.isFile == true, actual = null)

    /**
     * Hashes the cached archive (about 30 MB for the Ubuntu base) and compares it with the pin.
     * Blocking; the caller runs it off the main thread.
     *
     * @throws IOException if the archive cannot be read.
     */
    fun verify(pinned: PinnedImage?): ChecksumStatus {
        val actual = pinned?.archive?.takeIf { it.isFile }?.let { Sha256.of(it) }
        return classify(pinned?.digest, pinned?.archive?.isFile == true, actual)
    }

    /**
     * The runtime's cache key for an image: its URL's file name without `.tar.gz`, unsafe
     * characters replaced. This repeats `LinuxEnvironment.imageIdFor` (private in
     * `:sandbox-runtime`, which this slice does not edit) and is the only copy outside it;
     * [ImageChecksumTest] pins the derivation for the pinned catalog URLs.
     */
    fun archiveIdFor(tarballUrl: String): String =
        tarballUrl.substringAfterLast('/')
            .removeSuffix(".tar.gz")
            .replace(UNSAFE_NAME_CHARS, "_")
            .ifEmpty { FALLBACK_IMAGE_ID }

    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    private const val FALLBACK_IMAGE_ID = "rootfs"
}
