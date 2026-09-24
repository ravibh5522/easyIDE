package dev.easyide.app.extensions.registry

import dev.easyide.extensions.registry.Ed25519
import dev.easyide.extensions.registry.JdkEd25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The app's Tink verifier agrees with the JDK provider (decision 0016 amendment). */
class TinkEd25519Test {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** RFC 8032 sec 7.1 TEST 1-3: (public key, message, signature). */
    private val vectors = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    private val providers: List<Ed25519> = listOf(TinkEd25519, JdkEd25519)

    @Test fun `RFC 8032 vectors verify, and fail when altered, on both providers`() {
        for ((pk, msg, sig) in vectors) {
            for (p in providers) {
                assertTrue("$p $pk", p.verify(hex(pk), hex(msg), hex(sig)))
                assertFalse(p.verify(hex(pk), hex(msg) + 1, hex(sig)))
                assertFalse(p.verify(hex(pk), hex(msg), hex(sig).also { it[5] = (it[5] + 1).toByte() }))
            }
        }
    }

    @Test fun `Tink and JDK agree on random keys, messages and corruptions`() {
        val rnd = Random(20260924)
        repeat(40) { i ->
            val key = TestKey()
            val other = TestKey()
            val msg = rnd.nextBytes(rnd.nextInt(0, 512))
            val sig = key.sign(msg)
            val cases = listOf(
                Triple(key.raw, msg, sig),
                Triple(other.raw, msg, sig),
                Triple(key.raw, msg + byteArrayOf(i.toByte()), sig),
                Triple(key.raw, msg, sig.copyOf().also { it[rnd.nextInt(sig.size)] = (it[0] + 1).toByte() }),
                Triple(key.raw, msg, sig.copyOf(63)),
            )
            for ((pk, m, s) in cases) assertEquals("case $i", JdkEd25519.verify(pk, m, s), TinkEd25519.verify(pk, m, s))
            assertTrue(TinkEd25519.verify(key.raw, msg, sig))
            // A malformed key is a plain "does not verify" (JdkEd25519 throws on it instead).
            assertFalse(TinkEd25519.verify(key.raw.copyOf(31), msg, sig))
        }
    }
}
