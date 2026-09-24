package dev.easyide.app.extensions.registry

import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.ExtensionStateStore
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.registry.Hex
import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.KeyIds
import dev.easyide.extensions.registry.RegistrySigning
import dev.easyide.extensions.registry.SignedBytes
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An ed25519 key pair on the JDK provider (test-only: the app only verifies). */
class TestKey(private val pair: KeyPair = JdkEd25519.generate()) {
    val raw: ByteArray = JdkEd25519.raw(pair.public)
    val id: String = KeyIds.of(raw)
    fun sign(b: ByteArray): ByteArray = JdkEd25519.sign(pair.private, b)
    fun b64(): String = Base64.getEncoder().encodeToString(raw)
}

/**
 * The network port as an in-memory static host: ETags, 304s, a request log, and two failure
 * modes: [offline] throws IOException (a real network failure), [forbidden] throws
 * [AssertionError], so a test fails if anything touches the network at all.
 */
class FakeServer : HttpFetcher {
    val files = HashMap<String, ByteArray>()
    val requests = ArrayList<String>()
    var offline = false
    var forbidden = false

    override fun get(url: String, etag: String?, maxBytes: Long, into: File): FetchResult {
        if (forbidden) throw AssertionError("network used: $url")
        if (offline) throw IOException("offline")
        requests += url
        val body = files[url] ?: return FetchResult.Failed("$url: HTTP 404")
        val tag = "\"" + Hex.sha256(body).take(16) + "\""
        if (etag == tag) return FetchResult.NotModified
        if (body.size > maxBytes) return FetchResult.Failed("$url: over $maxBytes bytes")
        into.parentFile?.mkdirs()
        into.writeBytes(body)
        return FetchResult.Fetched(tag)
    }
}

/**
 * A signed static registry ("test" at [BASE]) with publisher `acme`, and one device: paths,
 * `state.json`, inventory, [LocalInstaller] and [RegistryService] over [server].
 * [device] builds another device process over the same files (a restart).
 */
class RegistryWorld(val root: File, val server: FakeServer = FakeServer(), val rootKey: TestKey = TestKey()) {
    val pub1 = TestKey()
    val pub2 = TestKey()
    val config = RegistryConfig("test", BASE, rootKey.raw)
    var now: Instant = Instant.parse("2026-09-24T10:00:00Z")
    private val entries = ArrayList<JsonObject>()
    val notices = ArrayList<String>()

    inner class Device {
        val paths = SandboxPaths(root).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { emptyList() }, state, Dispatchers.Unconfined)
        lateinit var service: RegistryService
        val installer = LocalInstaller(
            paths, state, inventory,
            ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined, clock = { 42L },
            isRevoked = { id, v -> service.isRevoked(id, v) },
        )

        init {
            service = RegistryService(
                paths, state, server, JdkEd25519, installer, inventory, { PackageLimits.DEFAULT.packageBytes }, Dispatchers.Unconfined,
                notify = { _, m -> notices += m }, clock = { now },
            )
        }

        suspend fun configure() = service.setConfigs(RegistryConfigs(listOf(config), emptyList()))

        fun entry(name: String, version: String): IndexEntry =
            service.view.value.catalog.items.first { it.id.value == "acme.$name" }.let { item ->
                service.client.cached(config)!!.index.entries.first { it.id == item.id && it.version.toString() == version }
            }

        /** Prepare and commit; the prepared result for assertions. */
        suspend fun install(name: String, version: String, allowNetwork: Boolean = true): RegistryPrepare {
            val r = service.prepare(config.id, entry(name, version), allowNetwork)
            if (r is RegistryPrepare.Ready) service.commit(r.staged, null)
            return r
        }
    }

    fun device() = Device()

    fun packageBytes(name: String, version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("package.json"))
            z.write("""{ "name": "$name", "publisher": "acme", "version": "$version", "displayName": "${name.uppercase()}", "engines": { "easyide": "^0.3.0" } }""".toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    fun packageUrl(name: String, version: String) = "https://pkgs.example.org/acme.$name-$version.easyext"

    /** Adds a publisher-signed entry to the next [publishIndex] and serves its package. */
    fun addEntry(name: String, version: String, key: TestKey = pub1, bytes: ByteArray = packageBytes(name, version)) {
        server.files[packageUrl(name, version)] = bytes
        entries += RegistrySigning.signEntry(json(mapOf(
            "id" to "acme.$name", "publisher" to "acme", "name" to name, "version" to version, "displayName" to name.uppercase(),
            "description" to "$name support", "categories" to listOf("Programming Languages"), "license" to "MIT",
            "engines" to mapOf("easyide" to "^0.3.0"), "scope" to "global", "layers" to listOf("L1"), "capabilities" to listOf<String>(),
            "url" to packageUrl(name, version), "size" to bytes.size, "sha256" to Hex.sha256(bytes), "publishedAt" to "2026-09-20T08:00:00Z",
        )) as JsonObject, key.id, key::sign)
    }

    /** Root-signs and serves `index.json` and `revocations.json`. */
    fun publishIndex(
        generatedAt: String = "2026-09-23T10:00:00Z",
        revocationsAt: String = "2026-09-22T00:00:00Z",
        revokedKeys: List<String> = emptyList(),
        revokedVersions: List<Pair<String, String>> = emptyList(),
        signer: TestKey = rootKey,
    ) {
        val index = json(mapOf("schemaVersion" to 1, "generatedAt" to generatedAt, "extensions" to entries.toList()))
        val rev = json(mapOf(
            "schemaVersion" to 1, "updatedAt" to revocationsAt,
            "keys" to revokedKeys.map { mapOf("keyId" to it, "reason" to "compromised") },
            "versions" to revokedVersions.map { (id, r) -> mapOf("id" to id, "versions" to r, "reason" to "malicious install step") },
        ))
        serve("index.json", index, signer)
        serve("revocations.json", rev, signer)
    }

    /** Root-signs and serves `publishers/acme.json` listing [keys]; [rotations] are (from, to) signed by `from`. */
    fun publishPublisher(keys: List<TestKey> = listOf(pub1), rotations: List<Pair<TestKey, TestKey>> = emptyList(), retired: Set<TestKey> = emptySet()) {
        val doc = json(mapOf(
            "publisher" to "acme", "displayName" to "Acme",
            "keys" to keys.map { mapOf("keyId" to it.id, "publicKey" to it.b64(), "status" to if (it in retired) "retired" else "active") },
            "rotation" to rotations.map { (from, to) ->
                mapOf("from" to from.id, "to" to to.id, "sigByOld" to Base64.getEncoder().encodeToString(from.sign(SignedBytes.rotation("acme", from.id, to.id))))
            },
        ))
        serve("publishers/acme.json", doc, rootKey)
    }

    private fun serve(name: String, doc: JsonElement, signer: TestKey) {
        server.files[BASE + name] = doc.toString().toByteArray()
        server.files["$BASE$name.sig"] = RegistrySigning.fileSig(doc, signer.id, signer::sign).toString().toByteArray()
    }

    companion object {
        const val BASE = "https://registry.example.org/idx/"

        fun json(v: Any?): JsonElement = when (v) {
            is String -> JsonPrimitive(v); is Int -> JsonPrimitive(v); is Long -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map(::json)); is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to json(x) })
            is JsonElement -> v; else -> error("$v")
        }
    }
}
