package dev.easyide.sandbox.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A loopback HTTP server serving one byte array, with switches for the server
 * behaviours [VerifiedDownloader] must survive. JDK-only, so the tests need no
 * extra dependency and no network.
 */
class TestFileServer(private val body: ByteArray) : AutoCloseable {

    /** Honour `Range: bytes=N-` with a 206; when false, always answer 200 with the full body. */
    @Volatile var honourRange = true

    /** Status to answer every request with instead of serving the body. */
    @Volatile var forcedStatus: Int? = null

    /** Send `Content-Length` (fixed-length) or omit it (chunked). */
    @Volatile var sendLength = true

    /** Declared body length larger than what is sent, simulating a dropped connection. */
    @Volatile var truncateTo: Int? = null

    /** Pause between [DRIP_CHUNK]-sized writes, so a test can act mid-transfer. */
    @Volatile var dripDelayMs: Long? = null

    /** Content-Range start to advertise on a 206 instead of the requested one. */
    @Volatile var wrongRangeStart: Long? = null

    val rangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val requestCount: Int get() = rangeHeaders.size

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(PATH) { exchange -> exchange.use { handle(it) } }
        executor = Executors.newCachedThreadPool()
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}$PATH"

    private fun handle(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        rangeHeaders += range
        forcedStatus?.let {
            exchange.sendResponseHeaders(it, -1)
            return
        }

        val start = range?.takeIf { honourRange }
            ?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
        if (start > body.size) {
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val slice = body.copyOfRange(start.toInt(), body.size)
        val status = if (range != null && honourRange) 206 else 200
        if (status == 206) {
            val advertised = wrongRangeStart ?: start
            exchange.responseHeaders.add("Content-Range", "bytes $advertised-${body.size - 1}/${body.size}")
        }

        val sent = truncateTo?.let { slice.copyOf(minOf(it, slice.size)) } ?: slice
        exchange.sendResponseHeaders(status, if (sendLength) slice.size.toLong() else 0)
        exchange.responseBody.use { out ->
            val drip = dripDelayMs
            if (drip == null) {
                out.write(sent)
                return
            }
            for (from in sent.indices step DRIP_CHUNK) {
                out.write(sent, from, minOf(DRIP_CHUNK, sent.size - from))
                out.flush()
                Thread.sleep(drip)
            }
        }
    }

    override fun close() = server.stop(0)

    companion object {
        const val DRIP_CHUNK = 16 * 1024
        private const val PATH = "/file.bin"
    }
}
