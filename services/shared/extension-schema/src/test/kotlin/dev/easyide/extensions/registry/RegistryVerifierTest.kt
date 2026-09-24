package dev.easyide.extensions.registry

import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.util.Base64

class RegistryVerifierTest {
    private class Key(val pair: KeyPair = JdkEd25519.generate()) {
        val raw = JdkEd25519.raw(pair.public)
        val id = KeyIds.of(raw)
        fun sign(b: ByteArray) = JdkEd25519.sign(pair.private, b)
        fun b64() = Base64.getEncoder().encodeToString(raw)
    }

    private val root = Key()
    private val pub1 = Key()
    private val pub2 = Key()
    private val verifier = RegistryVerifier(JdkEd25519)
    private val api = SemVer(0, 3, 0)

    private fun p(v: Any?): JsonElement = when (v) {
        is String -> JsonPrimitive(v); is Int -> JsonPrimitive(v); is Long -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::p)); is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to p(x) })
        is JsonElement -> v; else -> error("$v")
    }

    private val pkg = byteArrayOf(1, 2, 3, 4)

    private fun entry(name: String = "zig", version: String = "1.0.0", key: Key = pub1, engines: String = "^0.3.0"): JsonObject =
        RegistrySigning.signEntry(p(mapOf(
            "id" to "acme.$name", "publisher" to "acme", "name" to name, "version" to version, "displayName" to "Zig $version",
            "categories" to listOf("Programming Languages"), "license" to "MIT", "engines" to mapOf("easyide" to engines),
            "scope" to "global", "layers" to listOf("L1"), "capabilities" to listOf<String>(),
            "url" to "https://example.org/acme.$name-$version.easyext", "size" to pkg.size, "sha256" to Hex.sha256(pkg),
            "publishedAt" to "2026-09-20T08:00:00Z",
        )) as JsonObject, key.id, key::sign)

    private fun bytes(e: JsonElement) = e.toString().toByteArray()

    private fun index(vararg entries: JsonObject, at: String = "2026-09-23T10:00:00Z") =
        p(mapOf("schemaVersion" to 1, "generatedAt" to at, "minAppVersion" to "0.3.0", "extensions" to entries.toList()))

    private fun revocations(at: String = "2026-09-22T00:00:00Z", keys: List<String> = emptyList(), versions: List<Pair<String, String>> = emptyList()) =
        p(mapOf("schemaVersion" to 1, "updatedAt" to at,
            "keys" to keys.map { mapOf("keyId" to it, "reason" to "compromised") },
            "versions" to versions.map { (id, r) -> mapOf("id" to id, "versions" to r, "reason" to "malicious") }))

    private fun verify(index: JsonElement, rev: JsonElement = revocations(), previous: VerifiedIndex? = null, signer: Key = root) =
        verifier.verifyIndex("main", root.raw, bytes(index), bytes(RegistrySigning.fileSig(index, signer.id, signer::sign)),
            bytes(rev), bytes(RegistrySigning.fileSig(rev, signer.id, signer::sign)), previous)

    private fun ok(v: Verified<VerifiedIndex>) = (v as? Verified.Ok)?.value ?: error("rejected: $v")

    private fun publisher(rotation: List<Map<String, String>> = emptyList(), retired: Set<String> = emptySet()) = PublisherKeys.parse(p(mapOf(
        "publisher" to "acme", "keys" to listOf(pub1, pub2).map { mapOf("keyId" to it.id, "publicKey" to it.b64(), "status" to if (it.id in retired) "retired" else "active") },
        "rotation" to rotation,
    )) as JsonObject)

    @Test fun `a root-signed index verifies and exposes its entries`() {
        val idx = ok(verify(index(entry(), entry(version = "1.1.0"))))
        assertEquals(listOf("1.1.0", "1.0.0"), idx.versions(ExtensionId.parse("acme.zig")!!).map { it.version.toString() })
    }

    @Test fun `tampering, a wrong signer and rollback are hard failures`() {
        val signed = index(entry())
        val tampered = JsonObject(signed as JsonObject + ("generatedAt" to JsonPrimitive("2026-09-24T00:00:00Z")))
        val sig = RegistrySigning.fileSig(signed, root.id, root::sign)
        val rev = revocations()
        val r = verifier.verifyIndex("main", root.raw, bytes(tampered), bytes(sig), bytes(rev), bytes(RegistrySigning.fileSig(rev, root.id, root::sign)), null)
        assertTrue("$r", r is Verified.Rejected)
        assertTrue(verify(signed, signer = pub1) is Verified.Rejected)
        val newer = ok(verify(index(entry(), at = "2026-09-24T00:00:00Z")))
        assertTrue(verify(index(entry(), at = "2026-09-23T00:00:00Z"), previous = newer) is Verified.Rejected)
        assertTrue(verify(index(entry(), at = "2026-09-25T00:00:00Z"), revocations(at = "2026-09-01T00:00:00Z"), previous = newer) is Verified.Rejected)
    }

    @Test fun `duplicate keys, floats and bad shapes refuse the document`() {
        val dupKeys = """{"schemaVersion":1,"schemaVersion":1,"generatedAt":"2026-09-23T10:00:00Z","extensions":[]}""".toByteArray()
        val rev = revocations()
        val r = verifier.verifyIndex("main", root.raw, dupKeys, bytes(RegistrySigning.fileSig(index(), root.id, root::sign)), bytes(rev), bytes(RegistrySigning.fileSig(rev, root.id, root::sign)), null)
        assertTrue(r is Verified.Rejected)
        val http = JsonObject(entry() + ("url" to JsonPrimitive("http://example.org/x.easyext")))
        assertTrue(verify(index(http)) is Verified.Rejected)
        assertTrue(verify(index(entry(), entry())) is Verified.Rejected)
    }

    @Test fun `entry trust pins on first contact and refuses a changed key without rotation`() {
        val idx = ok(verify(index(entry(), entry(version = "2.0.0", key = pub2))))
        val (v1, v2) = idx.versions(ExtensionId.parse("acme.zig")!!).let { it[1] to it[0] }
        assertEquals(Verified.Ok(pub1.id), verifier.trustEntry(v1, publisher(), idx.revocations, pinned = null))
        assertEquals(Verified.Ok(pub1.id), verifier.trustEntry(v1, publisher(), idx.revocations, pinned = pub1.id))
        assertTrue(verifier.trustEntry(v2, publisher(), idx.revocations, pinned = pub1.id) is Verified.Rejected)
    }

    @Test fun `a rotation signed by the pinned key moves the pin`() {
        val idx = ok(verify(index(entry(version = "2.0.0", key = pub2))))
        val v2 = idx.entries.single()
        val good = mapOf("from" to pub1.id, "to" to pub2.id,
            "sigByOld" to Base64.getEncoder().encodeToString(pub1.sign(SignedBytes.rotation("acme", pub1.id, pub2.id))))
        assertEquals(Verified.Ok(pub2.id), verifier.trustEntry(v2, publisher(listOf(good)), idx.revocations, pinned = pub1.id))
        val forged = good + ("sigByOld" to Base64.getEncoder().encodeToString(pub2.sign(SignedBytes.rotation("acme", pub1.id, pub2.id))))
        assertTrue(verifier.trustEntry(v2, publisher(listOf(forged)), idx.revocations, pinned = pub1.id) is Verified.Rejected)
    }

    @Test fun `revoked or retired keys and revoked versions are refused`() {
        val idx = ok(verify(index(entry(), entry(version = "0.1.3")), revocations(versions = listOf("acme.zig" to "<=0.1.3"))))
        val (good, bad) = idx.versions(ExtensionId.parse("acme.zig")!!).let { it[0] to it[1] }
        assertTrue(verifier.trustEntry(good, publisher(), idx.revocations, null) is Verified.Ok)
        assertTrue(verifier.trustEntry(bad, publisher(), idx.revocations, null) is Verified.Rejected)
        val keyRevoked = ok(verify(index(entry()), revocations(keys = listOf(pub1.id))))
        assertTrue(verifier.trustEntry(keyRevoked.entries.single(), publisher(), keyRevoked.revocations, null) is Verified.Rejected)
        assertTrue(verifier.trustEntry(good, publisher(retired = setOf(pub1.id)), idx.revocations, null) is Verified.Rejected)
    }

    @Test fun `a tampered entry fails its publisher signature`() {
        val forged = JsonObject(entry() + ("url" to JsonPrimitive("https://evil.example/x.easyext")))
        val idx = ok(verify(index(forged)))
        assertTrue(verifier.trustEntry(idx.entries.single(), publisher(), idx.revocations, null) is Verified.Rejected)
    }

    @Test fun `package bytes must match size and sha256`() {
        val e = ok(verify(index(entry()))).entries.single()
        assertEquals(Verified.Ok(Unit), verifier.checkBytes(e, pkg))
        assertTrue(verifier.checkBytes(e, pkg + 5) is Verified.Rejected)
        assertTrue(verifier.checkBytes(e, byteArrayOf(9, 9, 9, 9)) is Verified.Rejected)
    }

    @Test fun `the catalog offers the newest compatible, unrevoked version and ranks search`() {
        val idx = ok(verify(
            index(entry(), entry(version = "1.1.0"), entry(version = "2.0.0", engines = "^1.0.0"), entry(name = "zigfmt")),
            revocations(versions = listOf("acme.zig" to "1.1.0")),
        ))
        val cat = Catalog(listOf(idx), api)
        val zig = cat.items.single { it.id.value == "acme.zig" }
        assertEquals("1.0.0", zig.latestCompatible!!.version.toString())
        assertEquals(listOf("2.0.0", "1.1.0", "1.0.0"), zig.versions.map { it.toString() })
        assertEquals(listOf("acme.zig", "acme.zigfmt"), cat.search("acme.zig").map { it.id.value })
        assertEquals(listOf("acme.zig", "acme.zigfmt"), cat.search("zig").map { it.id.value })
        assertEquals(emptyList<Any>(), cat.search("python"))
    }
}
