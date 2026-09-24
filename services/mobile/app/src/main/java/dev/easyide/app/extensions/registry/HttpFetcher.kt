package dev.easyide.app.extensions.registry

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What one GET did. The network is the boundary: every failure is a value with its reason. */
sealed interface FetchResult {
    /** The body is in the target file; [etag] is the server's, when it sent one. */
    data class Fetched(val etag: String?) : FetchResult

    /** 304 for the ETag sent: the cached copy is current; the target file is untouched. */
    data object NotModified : FetchResult

    data class Failed(val reason: String) : FetchResult
}

/**
 * The network port of the registry (registry-and-install.md sec 14): the app uses
 * [UrlConnectionFetcher], tests an in-memory server. Blocking; callers are on an IO dispatcher.
 */
fun interface HttpFetcher {
    /** GET [url] into [into] (overwritten), refusing bodies over [maxBytes]; [etag] becomes If-None-Match. */
    fun get(url: String, etag: String?, maxBytes: Long, into: File): FetchResult
}

/** [HttpFetcher] over [HttpURLConnection]: https only, timeouts and size caps from [RegistryPolicy]. */
class UrlConnectionFetcher : HttpFetcher {
    override fun get(url: String, etag: String?, maxBytes: Long, into: File): FetchResult {
        if (!url.startsWith(RegistryPolicy.SCHEME)) return FetchResult.Failed("refusing non-https URL $url")
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return FetchResult.Failed("$url: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            conn.connectTimeout = RegistryPolicy.CONNECT_TIMEOUT_MS
            conn.readTimeout = RegistryPolicy.READ_TIMEOUT_MS
            // HttpURLConnection never follows a redirect across protocols, so https stays https.
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            etag?.let { conn.setRequestProperty("If-None-Match", it) }
            val status = conn.responseCode
            when {
                !conn.url.toString().startsWith(RegistryPolicy.SCHEME) -> FetchResult.Failed("$url redirected to a non-https URL")
                status == HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                status != HttpURLConnection.HTTP_OK -> FetchResult.Failed("$url: HTTP $status")
                conn.contentLengthLong > maxBytes -> FetchResult.Failed("$url: ${conn.contentLengthLong} bytes exceeds the $maxBytes-byte limit")
                else -> copy(conn, url, maxBytes, into)
            }
        } catch (e: IOException) {
            FetchResult.Failed("$url: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /** Counts while streaming: Content-Length is not trusted. */
    private fun copy(conn: HttpURLConnection, url: String, maxBytes: Long, into: File): FetchResult {
        into.parentFile?.mkdirs()
        var total = 0L
        conn.inputStream.use { input ->
            FileOutputStream(into).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        into.delete()
                        return FetchResult.Failed("$url: body exceeds the $maxBytes-byte limit")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        return FetchResult.Fetched(conn.getHeaderField("ETag"))
    }

    private companion object { const val BUFFER = 64 * 1024 }
}
