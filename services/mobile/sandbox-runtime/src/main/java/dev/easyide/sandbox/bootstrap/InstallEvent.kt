package dev.easyide.sandbox.bootstrap

import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Structured progress of a Linux install, alongside the free-text
 * [ProgressReporter] lines. Text is right for a terminal log; a progress bar
 * needs numbers, and parsing them back out of log lines would be brittle.
 */
sealed interface InstallEvent {

    /** proot is being unpacked from the APK. Fast, but the first thing that happens. */
    data object PreparingRuntime : InstallEvent

    /**
     * The rootfs tarball is downloading. [bytes] counts the whole file, including a
     * resumed prefix of [resumedFrom] bytes kept from an earlier attempt;
     * [totalBytes] is null when the server did not say.
     */
    data class Downloading(val bytes: Long, val totalBytes: Long?, val resumedFrom: Long) : InstallEvent

    /** The tarball is unpacking. [bytesRead] and [totalBytes] are of the compressed archive. */
    data class Extracting(val bytesRead: Long, val totalBytes: Long) : InstallEvent

    /** Preset setup command [step] of [stepCount] (1-based) is running. */
    data class Setup(val step: Int, val stepCount: Int, val command: String) : InstallEvent
}

/**
 * Wraps the archive stream handed to the extractor so that unpacking, a long
 * blocking call the extractor itself cannot interrupt, still observes
 * cancellation and reports how far through the archive it is.
 *
 * Reports at most every [REPORT_STEP_BYTES], which is fine-grained enough for
 * a smooth bar without one event per 8 KB read.
 */
internal class ObservedInputStream(
    source: InputStream,
    private val context: CoroutineContext,
    private val totalBytes: Long,
    private val onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
) : FilterInputStream(source) {

    private var bytesRead = 0L
    private var lastReported = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(count: Long) {
        context.ensureActive()
        bytesRead += count
        if (bytesRead - lastReported >= REPORT_STEP_BYTES || bytesRead >= totalBytes) {
            lastReported = bytesRead
            onProgress(bytesRead, totalBytes)
        }
    }

    private companion object {
        const val REPORT_STEP_BYTES = 512L * 1024
    }
}
