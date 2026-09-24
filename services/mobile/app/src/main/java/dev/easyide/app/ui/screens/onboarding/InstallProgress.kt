package dev.easyide.app.ui.screens.onboarding

import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.bootstrap.InstallEvent
import dev.easyide.sandbox.download.DownloadError
import java.io.IOException

/**
 * Overall progress of an install as one 0..1 number across its phases, or null
 * when it cannot be known yet (a download whose size the server did not say).
 *
 * The phases are weighted by how long they usually take on a tablet: the
 * download and the archive unpack dominate a bare image, and a preset's package
 * install (apt) takes the last fifth. A cached image skips the download, so the
 * bar jumps forward - it never runs backwards.
 */
fun overallFraction(event: InstallEvent, hasSetup: Boolean): Float? {
    val downloadEnd = if (hasSetup) DOWNLOAD_END_WITH_SETUP else DOWNLOAD_END_BARE
    val extractEnd = if (hasSetup) EXTRACT_END_WITH_SETUP else 1f
    return when (event) {
        InstallEvent.PreparingRuntime -> 0f
        is InstallEvent.Downloading -> event.totalBytes?.takeIf { it > 0 }?.let { total ->
            PREPARE_END + (downloadEnd - PREPARE_END) * (event.bytes.toFloat() / total).coerceIn(0f, 1f)
        }
        is InstallEvent.Extracting -> {
            val done = if (event.totalBytes > 0) (event.bytesRead.toFloat() / event.totalBytes).coerceIn(0f, 1f) else 0f
            downloadEnd + (extractEnd - downloadEnd) * done
        }
        is InstallEvent.Setup -> extractEnd + (1f - extractEnd) * ((event.step - 1).toFloat() / event.stepCount)
    }
}

/**
 * The bar value after [event], never lower than [previous]: a resumed download
 * or a cached image makes phases start "ahead", and a bar that steps back reads
 * as a failure. Unknown ([overallFraction] null) keeps what was shown.
 */
fun nextFraction(previous: Float?, event: InstallEvent, hasSetup: Boolean): Float? {
    val next = overallFraction(event, hasSetup) ?: return previous
    return if (previous == null) next else maxOf(previous, next)
}

private const val PREPARE_END = 0.02f
private const val DOWNLOAD_END_WITH_SETUP = 0.55f
private const val DOWNLOAD_END_BARE = 0.65f
private const val EXTRACT_END_WITH_SETUP = 0.80f

/** Why an install stopped, in terms the user can act on. */
enum class InstallFailure(val retryable: Boolean) {
    /** No route to the host, or the connection dropped. The partial download is kept. */
    NETWORK(true),

    /** The server answered with an error status. */
    SERVER(true),

    /** The file arrived but did not match its published digest; it was discarded. */
    CORRUPT_DOWNLOAD(true),

    /** No space left on the device. */
    STORAGE_FULL(true),

    /** Another storage failure (permissions, rename). */
    STORAGE(true),

    /** This device has no build of the sandbox runtime or rootfs for its CPU. */
    UNSUPPORTED_DEVICE(false),

    /** A preset's package install failed after the base system was unpacked. */
    SETUP_STEP(true),

    UNKNOWN(true),
}

/** [detail] is the technical reason (HTTP code, failing command) to show under the advice. */
data class ClassifiedFailure(val kind: InstallFailure, val detail: String?)

/**
 * Reads an install exception into an [InstallFailure]. The provisioner wraps the
 * downloader's typed errors in [SandboxError.ProvisioningFailed], so the whole
 * cause chain is searched, outermost first.
 */
fun classifyInstallFailure(error: Throwable): ClassifiedFailure {
    val chain = generateSequence(error) { it.cause }.toList()
    chain.forEach { cause ->
        when (cause) {
            is DownloadError.HttpStatus -> return ClassifiedFailure(InstallFailure.SERVER, "HTTP ${cause.code}")
            is DownloadError.Network -> return ClassifiedFailure(InstallFailure.NETWORK, cause.cause?.message)
            is DownloadError.Integrity -> return ClassifiedFailure(InstallFailure.CORRUPT_DOWNLOAD, null)
            is DownloadError.TooLarge -> return ClassifiedFailure(InstallFailure.CORRUPT_DOWNLOAD, null)
            is DownloadError.Storage -> return storageFailure(cause)
            is SandboxError.BackendUnavailable -> return ClassifiedFailure(InstallFailure.UNSUPPORTED_DEVICE, cause.message)
        }
    }
    chain.forEach { cause ->
        val message = cause.message.orEmpty()
        when {
            cause is SandboxError.ProvisioningFailed && "has no rootfs for" in message ->
                return ClassifiedFailure(InstallFailure.UNSUPPORTED_DEVICE, message)
            cause is SandboxError.ProvisioningFailed && "setup step" in message ->
                return ClassifiedFailure(InstallFailure.SETUP_STEP, message.substringAfter("failed: ", message))
            cause is IOException && NO_SPACE in message -> return ClassifiedFailure(InstallFailure.STORAGE_FULL, null)
        }
    }
    return ClassifiedFailure(InstallFailure.UNKNOWN, error.message)
}

private fun storageFailure(cause: Throwable): ClassifiedFailure {
    val full = generateSequence(cause) { it.cause }.any { NO_SPACE in it.message.orEmpty() }
    return ClassifiedFailure(if (full) InstallFailure.STORAGE_FULL else InstallFailure.STORAGE, cause.message)
}

private const val NO_SPACE = "No space left"
