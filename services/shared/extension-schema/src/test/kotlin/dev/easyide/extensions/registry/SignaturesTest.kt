package dev.easyide.extensions.registry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturesTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 sec 7.1, TEST 2 (one-byte message 0x72).
    private val rfcPublic = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val rfcMessage = hex("72")
    private val rfcSig = hex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    @Test fun `jdk provider verifies the RFC 8032 vector`() {
        assertTrue(JdkEd25519.verify(rfcPublic, rfcMessage, rfcSig))
        assertFalse(JdkEd25519.verify(rfcPublic, hex("73"), rfcSig))
    }

    @Test fun `key id is the first 16 hex chars of sha256 of the raw key`() {
        val id = KeyIds.of(rfcPublic)
        assertEquals(Hex.sha256(rfcPublic).substring(0, 16), id)
        assertTrue(KeyIds.isValid(id))
    }

    @Test fun `entry signature covers the entry without its signature member`() {
        val pair = JdkEd25519.generate()
        val raw = JdkEd25519.raw(pair.public)
        val entry = JsonObject(mapOf("id" to JsonPrimitive("acme.zig"), "version" to JsonPrimitive("1.0.0"), "size" to JsonPrimitive(10)))
        val sig = Sig(KeyIds.of(raw), Sig.ALG, JdkEd25519.sign(pair.private, SignedBytes.entry(entry)))
        val signed = JsonObject(entry + ("signature" to sig.toJson()))
        val verifier = SignatureVerifier(JdkEd25519)
        assertTrue(verifier.verifyEntry(signed, raw))
        assertFalse(verifier.verifyEntry(JsonObject(signed + ("version" to JsonPrimitive("1.0.1"))), raw))
        // A signature under another key id is refused before the crypto runs.
        assertFalse(verifier.verify(SignedBytes.entry(entry), sig.copy(keyId = "0000000000000000"), raw))
    }

    @Test fun `sig json round trips and rejects malformed shapes`() {
        val sig = Sig("0011223344556677", Sig.ALG, byteArrayOf(1, 2, 3))
        assertEquals(sig, Sig.fromJson(sig.toJson()))
        assertNull(Sig.fromJson(JsonObject(sig.toJson() + ("alg" to JsonPrimitive("rsa")))))
        assertNull(Sig.fromJson(JsonObject(sig.toJson() + ("keyId" to JsonPrimitive("XYZ")))))
        assertNull(Sig.fromJson(JsonObject(sig.toJson() + ("sig" to JsonPrimitive("not base64!")))))
    }
}
