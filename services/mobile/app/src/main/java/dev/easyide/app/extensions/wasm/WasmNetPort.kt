package dev.easyide.app.extensions.wasm

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.host.HandleSink
import dev.easyide.extwasm.host.NetGuard
import dev.easyide.extwasm.host.NetPort
import dev.easyide.extwasm.host.NetRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException

/** Opens one connection; tests substitute a fake [HttpURLConnection]. */
fun interface HttpOpener {
    fun open(url: URL): HttpURLConnection
}

/**
 * `net.fetch` over [HttpURLConnection] (pattern of `RootfsProvisioner.download`). The host
 * router has checked the scheme (https) and the declared `network(...)` host of the first
 * URL; this port applies what only the connection can see, on every hop:
 * - every resolved address must be public ([NetGuard.isPublic]): loopback, link-local,
 *   private, CGNAT, multicast and unspecified addresses are refused;
 * - redirects are followed by hand, only to https URLs whose host is declared
 *   ([NetRequest.hostAllowed]), at most [MAX_REDIRECTS];
 * - the body is read up to [NetRequest.maxResponseBytes]; a longer one fails E_LIMIT.
 *
 * The call returns at once; the outcome arrives as one `net.response` event carrying
 * `{status, headers, body}` or `{error: {code, message}}`. Known gap: the address check
 * and the connection resolve the name separately (DNS rebinding between them is possible).
 */
class WasmNetPort(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val opener: HttpOpener = HttpOpener { url -> url.openConnection() as HttpURLConnection },
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : NetPort {

    override suspend fun fetch(extensionId: String, handle: Long, request: NetRequest, sink: HandleSink) {
        scope.launch(io) { sink.finish(EVENT, run(request)) }
    }

    /** The whole exchange; visible for tests. Never throws: failures become the event's `error`. */
    internal fun run(request: NetRequest): JsonObject {
        var url = request.url
        var method = request.method
        var body = request.body
        repeat(MAX_REDIRECTS + 1) {
            refusal(url, request)?.let { return error(ErrorCode.E_CAPABILITY, it) }
            val conn = try {
                opener.open(url.toURL())
            } catch (e: IOException) {
                return error(ErrorCode.E_UNAVAILABLE, "cannot connect to ${url.host}: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return error(ErrorCode.E_ARGS, "invalid URL")
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = method
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                body?.let { b ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(b.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val location = conn.getHeaderField(LOCATION)
                if (status in REDIRECT_CODES && location != null) {
                    url = try {
                        url.resolve(location)
                    } catch (e: URISyntaxException) {
                        return error(ErrorCode.E_INTERNAL, "redirect to an invalid URL")
                    } catch (e: IllegalArgumentException) {
                        return error(ErrorCode.E_INTERNAL, "redirect to an invalid URL")
                    }
                    if (status != TEMPORARY_REDIRECT && status != PERMANENT_REDIRECT) { method = GET; body = null }
                    return@repeat
                }
                val stream = if (status >= HTTP_ERROR) conn.errorStream else conn.inputStream
                val bytes = if (stream == null) ByteArray(0) else stream.use { capped(it, request.maxResponseBytes) }
                    ?: return error(ErrorCode.E_LIMIT, "response larger than ${request.maxResponseBytes} bytes (extensions.wasm.netMaxResponseKb)")
                return buildJsonObject {
                    put(STATUS, status)
                    put(HEADERS, JsonObject(conn.headerFields.orEmpty().filterKeys { it != null }.mapValues { (_, v) -> JsonPrimitive(v.joinToString(", ")) }))
                    put(BODY, String(bytes, Charsets.UTF_8))
                }
            } catch (e: IOException) {
                return error(ErrorCode.E_UNAVAILABLE, "${url.host}: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
        return error(ErrorCode.E_LIMIT, "more than $MAX_REDIRECTS redirects")
    }

    /** Why [url] must not be fetched, or null. */
    private fun refusal(url: URI, request: NetRequest): String? {
        if (!url.scheme.equals(HTTPS, ignoreCase = true)) return "only https URLs are allowed (${url.scheme})"
        val host = url.host ?: return "the URL has no host"
        if (!request.hostAllowed(host)) return "network($host) not granted"
        val addresses = try {
            resolve(host)
        } catch (e: UnknownHostException) {
            return "cannot resolve $host"
        }
        if (addresses.isEmpty()) return "cannot resolve $host"
        return addresses.firstOrNull { !NetGuard.isPublic(it) }?.let { "$host resolves to a non-public address (${it.hostAddress})" }
    }

    /** Up to [max] bytes, or null when the stream holds more. */
    private fun capped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
    }

    private fun error(code: ErrorCode, message: String) = buildJsonObject {
        put(ERROR, buildJsonObject { put(CODE, code.name); put(MESSAGE, message) })
    }

    companion object {
        const val EVENT = "net.response"
        const val MAX_REDIRECTS = 5
        const val DEFAULT_TIMEOUT_MS = 15_000
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val TEMPORARY_REDIRECT = 307
        private const val PERMANENT_REDIRECT = 308
        private const val HTTP_ERROR = 400
        private const val BUFFER = 8192
        private const val HTTPS = "https"
        private const val GET = "GET"
        private const val LOCATION = "Location"
        private const val STATUS = "status"
        private const val HEADERS = "headers"
        private const val BODY = "body"
        private const val ERROR = "error"
        private const val CODE = "code"
        private const val MESSAGE = "message"
    }
}
