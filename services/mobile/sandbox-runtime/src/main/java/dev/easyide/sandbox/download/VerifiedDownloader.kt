package dev.easyide.sandbox.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * The one HTTP downloader for anything whose bytes must be exactly what a
 * trusted source said they would be: rootfs images today, extension packages
 * and registry payloads later.
 *
 * Guarantees, in order of importance:
 * - [DownloadRequest.destination] only ever appears holding bytes whose SHA-256
 *   equals [DownloadRequest.sha256]. A mismatch is a hard failure with the
 *   partial file deleted; there is no "use it anyway".
 * - The digest is computed while streaming, so a completed download is never
 *   read a second time just to be checked.
 * - An interrupted download resumes from its `.part` file with an HTTP Range
 *   request; the prefix already on disk is hashed once to seed the digest.
 *   A server that ignores or botches the Range restarts cleanly from zero.
 * - An existing [DownloadRequest.destination] is re-hashed rather than trusted,
 *   so a corrupted cache heals itself instead of failing later.
 * - Cancelling the collector stops the transfer at the next chunk and keeps the
 *   `.part` file for resume. The connection is also disconnected from outside
 *   the reading coroutine, which aborts a read blocked on a stalled socket
 *   where the platform's HttpURLConnection supports cross-thread disconnect
 *   (Android's OkHttp-based one cancels the call); the JDK's does not, so there
 *   a stalled read ends at [DownloadPolicy.READ_TIMEOUT_MS].
 */
class VerifiedDownloader(private val ioDispatcher: CoroutineDispatcher) {

    /** Cold: nothing happens until collected, and each collection is one attempt. */
    fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val destination = request.destination
        if (destination.isFile) {
            if (hashFile(destination) == request.sha256) {
                emit(DownloadEvent.Verified(destination, destination.length(), fromCache = true))
                return@flow
            }
            delete(destination)
        }
        destination.parentFile?.let(::ensureDirectory)

        coroutineScope {
            // Blocking socket reads do not observe coroutine cancellation, so a
            // sibling disconnects from outside when the scope is cancelled; a
            // read that fails because of it is reported as cancellation.
            val live = AtomicReference<HttpURLConnection?>()
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    live.getAndSet(null)?.disconnect()
                }
            }
            try {
                transfer(request, live) { emit(it) }
            } finally {
                closer.cancel()
            }
        }
    }.flowOn(ioDispatcher)

    private suspend fun transfer(
        request: DownloadRequest,
        live: AtomicReference<HttpURLConnection?>,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        val part = partFileFor(request.destination)
        if (part.isFile && part.length() > request.maxBytes) delete(part)
        var offset = if (part.isFile) part.length() else 0L

        var connection = connect(request.url, offset, live)
        if (offset > 0 && mustRestart(connection, offset)) {
            connection.disconnect()
            delete(part)
            offset = 0
            connection = connect(request.url, offset, live)
        }

        try {
            val code = responseCode(connection, request.url)
            if (code !in HTTP_SUCCESS) throw DownloadError.HttpStatus(request.url, code)

            val total = connection.contentLengthLong.takeIf { it >= 0 }?.let { it + offset }
            if (total != null && total > request.maxBytes) {
                delete(part)
                throw DownloadError.TooLarge(request.url, request.maxBytes)
            }

            val digest = if (offset > 0) digestFile(part) else Sha256.newDigest()
            emit(DownloadEvent.Started(resumedFrom = offset, totalBytes = total))

            val received = copy(request, connection, part, offset, total, digest, emit)
            if (total != null && received < total) {
                throw DownloadError.Network(
                    request.url,
                    IOException("connection closed after $received of $total bytes"),
                )
            }

            val actual = Sha256.fromDigest(digest.digest())
            if (actual != request.sha256) {
                delete(part)
                throw DownloadError.Integrity(request.url, request.sha256, actual)
            }
            publish(part, request.destination)
            emit(DownloadEvent.Verified(request.destination, received, fromCache = false))
        } finally {
            live.set(null)
            connection.disconnect()
        }
    }

    /**
     * Streams the body into [part], appending when resuming, feeding [digest]
     * and enforcing [DownloadRequest.maxBytes] on what actually arrives.
     *
     * @return total file length after the copy, resumed prefix included.
     */
    private suspend fun copy(
        request: DownloadRequest,
        connection: HttpURLConnection,
        part: File,
        offset: Long,
        total: Long?,
        digest: MessageDigest,
        emit: suspend (DownloadEvent) -> Unit,
    ): Long {
        val input = network(request.url) { connection.inputStream }
        var written = offset
        var nextReport = offset + DownloadPolicy.PROGRESS_STEP_BYTES

        input.use {
            storage(part) { FileOutputStream(part, offset > 0) }.use { output ->
                val buffer = ByteArray(DownloadPolicy.BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = readChunk(request.url, input, buffer)
                    if (read < 0) break
                    written += read
                    if (written > request.maxBytes) {
                        output.close()
                        delete(part)
                        throw DownloadError.TooLarge(request.url, request.maxBytes)
                    }
                    storage(part) { output.write(buffer, 0, read) }
                    digest.update(buffer, 0, read)
                    if (written >= nextReport) {
                        nextReport = written + DownloadPolicy.PROGRESS_STEP_BYTES
                        emit(DownloadEvent.Progress(written, total))
                    }
                }
            }
        }
        emit(DownloadEvent.Progress(written, total))
        return written
    }

    /**
     * Whether a resume attempt has to be abandoned and the file fetched from
     * byte zero. Only a correct `206` with a `Content-Range` starting exactly at
     * [offset] continues the old bytes: `200` means the server ignored the
     * Range, `416` means the part is not a prefix of what the server now has.
     * Any other status falls through to the normal error path with the part
     * kept, because a 5xx says nothing about the bytes on disk.
     */
    private suspend fun mustRestart(connection: HttpURLConnection, offset: Long): Boolean {
        val url = connection.url.toString()
        return when (responseCode(connection, url)) {
            HttpURLConnection.HTTP_OK, HTTP_RANGE_NOT_SATISFIABLE -> true
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = connection.getHeaderField(HEADER_CONTENT_RANGE)
                range == null || !range.trim().startsWith("$RANGE_UNIT $offset-")
            }
            else -> false
        }
    }

    private suspend fun connect(
        url: String,
        offset: Long,
        live: AtomicReference<HttpURLConnection?>,
    ): HttpURLConnection {
        val connection = network(url) { URL(url).openConnection() as HttpURLConnection }.apply {
            connectTimeout = DownloadPolicy.CONNECT_TIMEOUT_MS
            readTimeout = DownloadPolicy.READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // The digest and the Range offsets are over the bytes as stored on
            // the server. Transparent gzip (Android's default) would hash and
            // count decoded bytes instead, and drop Content-Length entirely.
            setRequestProperty(HEADER_ACCEPT_ENCODING, IDENTITY)
            // No keep-alive: disconnect() on a pooled connection may try to
            // drain the rest of the body for reuse, which would turn the
            // cancellation hook into another blocked read.
            setRequestProperty(HEADER_CONNECTION, CLOSE)
            if (offset > 0) setRequestProperty(HEADER_RANGE, "$RANGE_UNIT=$offset-")
        }
        live.set(connection)
        return connection
    }

    private suspend fun responseCode(connection: HttpURLConnection, url: String): Int =
        network(url) { connection.responseCode }

    private suspend fun readChunk(url: String, input: InputStream, buffer: ByteArray): Int =
        network(url) { input.read(buffer) }

    /**
     * Runs a network call, mapping I/O failure to [DownloadError.Network] -
     * unless the coroutine was cancelled, in which case the failure is just the
     * socket being closed by the cancellation hook and cancellation propagates.
     */
    private suspend inline fun <T> network(url: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw DownloadError.Network(url, e)
        }

    private inline fun <T> storage(file: File, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DownloadError.Storage(file.absolutePath, e)
        }

    private fun hashFile(file: File): Sha256 = storage(file) { Sha256.of(file) }

    private fun digestFile(file: File): MessageDigest = storage(file) { Sha256.digestOf(file) }

    /** Same-directory rename, so the verified file appears atomically or not at all. */
    private fun publish(part: File, destination: File) {
        storage(destination) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun delete(file: File) {
        if (file.exists() && !file.delete()) throw DownloadError.Storage(file.absolutePath, null)
    }

    private fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw DownloadError.Storage(dir.absolutePath, null)
    }

    private companion object {
        val HTTP_SUCCESS = 200..299
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HEADER_RANGE = "Range"
        const val HEADER_CONTENT_RANGE = "Content-Range"
        const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
        const val IDENTITY = "identity"
        const val HEADER_CONNECTION = "Connection"
        const val CLOSE = "close"
        const val RANGE_UNIT = "bytes"

        fun partFileFor(destination: File): File =
            File(destination.parentFile, destination.name + DownloadPolicy.PART_SUFFIX)
    }
}
