package dev.easyide.app.extensions.wasm

import dev.easyide.extwasm.host.HandleSink
import dev.easyide.extwasm.host.HostMatcher
import dev.easyide.extwasm.host.NetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** `net.fetch` rules only the connection can apply: resolved addresses, redirects, body cap. */
class WasmNetPortTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = ArrayList<URL>()

    @After fun tearDown() = scope.cancel()

    /** A canned response per URL. */
    private class FakeConnection(url: URL, private val status: Int, private val location: String?, private val body: String) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getHeaderFields(): Map<String?, List<String>> = mapOf("Content-Type" to listOf("text/plain"))
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    }

    private fun port(
        addresses: Map<String, String> = emptyMap(),
        responses: (URL) -> FakeConnection = { FakeConnection(it, 200, null, "hello") },
    ) = WasmNetPort(
        scope, Dispatchers.IO,
        resolve = { host -> listOf(InetAddress.getByName(addresses[host] ?: "93.184.216.34")) },
        opener = { url -> opened += url; responses(url) },
    )

    private fun request(url: String, hosts: List<String> = listOf("api.example.com"), max: Int = 1024) =
        NetRequest(URI(url), "GET", emptyMap(), null, max, HostMatcher(hosts)::matches)

    private fun JsonObject.code(): String? = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test fun `a declared public https host is fetched and answered as one net response event`() {
        val done = CompletableFuture<Pair<String, JsonObject>>()
        val sink = object : HandleSink {
            override fun emit(event: String, data: JsonObject) = Unit
            override fun finish(event: String, data: JsonObject) { done.complete(event to data) }
        }
        runBlocking { port().fetch("acme.x", 1, request("https://api.example.com/v1"), sink) }
        val (event, data) = done.get(10, TimeUnit.SECONDS)
        assertEquals("net.response", event)
        assertEquals(200, data["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("hello", data["body"]!!.jsonPrimitive.content)
    }

    @Test fun `loopback, private and link-local addresses are refused before connecting`() {
        for (address in listOf("127.0.0.1", "10.0.0.8", "192.168.1.2", "169.254.169.254", "::1", "100.64.0.1")) {
            val out = port(mapOf("api.example.com" to address)).run(request("https://api.example.com/"))
            assertEquals(address, "E_CAPABILITY", out.code())
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun `redirects are followed only to declared https hosts`() {
        val toEvil = port(responses = { url ->
            if (url.host == "api.example.com") FakeConnection(url, 302, "https://evil.test/steal", "") else FakeConnection(url, 200, null, "evil")
        })
        assertEquals("E_CAPABILITY", toEvil.run(request("https://api.example.com/")).code())
        assertEquals(listOf("api.example.com"), opened.map { it.host })

        opened.clear()
        val downgrade = port(responses = { url -> FakeConnection(url, 301, "http://api.example.com/plain", "") })
        assertEquals("E_CAPABILITY", downgrade.run(request("https://api.example.com/")).code())

        opened.clear()
        val declared = port(responses = { url ->
            if (url.path == "/old") FakeConnection(url, 307, "/new", "") else FakeConnection(url, 200, null, "moved")
        })
        val out = declared.run(request("https://api.example.com/old"))
        assertEquals("moved", out["body"]!!.jsonPrimitive.content)
        assertEquals(listOf("/old", "/new"), opened.map { it.path })

        val loop = port(responses = { url -> FakeConnection(url, 302, "/again", "") })
        assertEquals("E_LIMIT", loop.run(request("https://api.example.com/")).code())
    }

    @Test fun `plain http and undeclared hosts are refused, and bodies over the cap fail E_LIMIT`() {
        assertEquals("E_CAPABILITY", port().run(request("http://api.example.com/")).code())
        assertEquals("E_CAPABILITY", port().run(request("https://other.example.com/")).code())
        val big = port(responses = { url -> FakeConnection(url, 200, null, "x".repeat(2000)) })
        assertEquals("E_LIMIT", big.run(request("https://api.example.com/", max = 1024)).code())
    }
}
