package dev.easyide.extensions.registry

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * [Ed25519] on the JDK provider (Java 15+, JEP 339). What `easyide-ext` and the JVM tests
 * use. Not for the app below API 33 until the provider question in registry-and-install.md
 * sec 4.2 is settled.
 */
object JdkEd25519 : Ed25519 {
    private const val ALGORITHM = "Ed25519"

    /** X.509 SubjectPublicKeyInfo header for an Ed25519 key (RFC 8410); the raw key follows it. */
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey(publicKey))
            update(message)
            verify(signature)
        }
    } catch (e: GeneralSecurityException) {
        false
    }

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray = Signature.getInstance(ALGORITHM).run {
        initSign(privateKey)
        update(message)
        sign()
    }

    fun generate(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    /** Raw 32-byte key: the tail of the SPKI encoding. */
    fun raw(key: PublicKey): ByteArray {
        val der = key.encoded
        require(der.size == SPKI_PREFIX.size + KeyIds.RAW_KEY_BYTES) { "not an Ed25519 public key" }
        return der.copyOfRange(SPKI_PREFIX.size, der.size)
    }

    fun publicKey(raw: ByteArray): PublicKey {
        require(raw.size == KeyIds.RAW_KEY_BYTES) { "ed25519 public key must be ${KeyIds.RAW_KEY_BYTES} bytes" }
        return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
    }
}
