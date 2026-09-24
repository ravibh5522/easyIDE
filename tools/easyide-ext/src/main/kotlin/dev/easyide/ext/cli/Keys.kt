package dev.easyide.ext.cli

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.KeyIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.EdECPrivateKey
import java.security.spec.NamedParameterSpec
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec

/** A publisher key pair loaded from disk. */
class PublisherKey(val private: PrivateKey, val public: PublicKey) {
    val raw: ByteArray get() = JdkEd25519.raw(public)
    val keyId: String get() = KeyIds.of(raw)
}

/**
 * cli.md sec 5.4 key storage: PKCS#8 PEM, optionally PBES2-encrypted (PBKDF2-HMAC-SHA256 +
 * AES-256) with JDK APIs only. Private key bytes never reach stdout or `--json` output.
 */
object Keys {
    const val PASSPHRASE_ENV = "EASYIDE_EXT_KEY_PASSPHRASE"
    private const val PBE = "PBEWithHmacSHA256AndAES_256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 16
    private const val PLAIN = "PRIVATE KEY"
    private const val ENCRYPTED = "ENCRYPTED PRIVATE KEY"

    fun defaultDir(ctx: CliContext) = File(ctx.home, ".easyide/keys")

    fun privatePem(key: PrivateKey, passphrase: CharArray?): String {
        val der = key.encoded
        if (passphrase == null || passphrase.isEmpty()) return pem(PLAIN, der)
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(rnd::nextBytes)
        val iv = ByteArray(IV_BYTES).also(rnd::nextBytes)
        val secret = SecretKeyFactory.getInstance(PBE).generateSecret(PBEKeySpec(passphrase))
        val cipher = Cipher.getInstance(PBE)
        cipher.init(Cipher.ENCRYPT_MODE, secret, PBEParameterSpec(salt, PBKDF2_ITERATIONS, IvParameterSpec(iv)))
        return pem(ENCRYPTED, EncryptedKeyInfo.encode(cipher.parameters.encoded, cipher.doFinal(der)))
    }

    /** `{"keyId","publicKey"}`: the fragment that goes into `publishers/<p>.json`. */
    fun publicJson(key: PublisherKey): JsonObject = JsonObject(
        mapOf("keyId" to JsonPrimitive(key.keyId), "publicKey" to JsonPrimitive(Base64.getEncoder().encodeToString(key.raw))),
    )

    /** Loads a private key file; asks for the passphrase (env first, then the terminal) only when it is encrypted. */
    fun load(ctx: CliContext, file: File): PublisherKey {
        if (!file.isFile) ioFailure("key file not found: ${file.path}", file.path)
        val text = String(file.readBytesOrFail(), Charsets.US_ASCII)
        val (label, der) = unpem(text) ?: throw CliFailure(CliCode.KEY, "not a PEM private key", ExitCode.VALIDATION, file.path)
        val pkcs8 = when (label) {
            PLAIN -> der
            ENCRYPTED -> decrypt(der, passphrase(ctx, "Passphrase for ${file.name}: ") ?: throw CliFailure(
                CliCode.KEY, "key is encrypted; set $PASSPHRASE_ENV or run in a terminal", ExitCode.VALIDATION, file.path,
            ), file)
            else -> throw CliFailure(CliCode.KEY, "unsupported PEM block '$label'", ExitCode.VALIDATION, file.path)
        }
        val private = try {
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: GeneralSecurityException) {
            throw CliFailure(CliCode.KEY, "not an Ed25519 private key", ExitCode.VALIDATION, file.path)
        }
        return PublisherKey(private, publicOf(private))
    }

    /** Reads a `.pub.json` fragment: raw public key bytes, checked against its keyId. */
    fun loadPublic(file: File): Pair<String, ByteArray> {
        val text = String(file.readBytesOrFail(), Charsets.UTF_8)
        val obj = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject
            ?: throw CliFailure(CliCode.KEY, "not a public key JSON file", ExitCode.VALIDATION, file.path)
        val raw = obj["publicKey"]?.stringOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == KeyIds.RAW_KEY_BYTES }
            ?: throw CliFailure(CliCode.KEY, "publicKey must be a base64 raw 32-byte Ed25519 key", ExitCode.VALIDATION, file.path)
        val keyId = KeyIds.of(raw)
        obj["keyId"]?.stringOrNull?.let { if (it != keyId) throw CliFailure(CliCode.KEY, "keyId $it does not match the key ($keyId)", ExitCode.VALIDATION, file.path) }
        return keyId to raw
    }

    fun passphrase(ctx: CliContext, prompt: String): CharArray? =
        ctx.env[PASSPHRASE_ENV]?.toCharArray() ?: ctx.readSecret(prompt)

    /** Creates [dir] as 0700 and [file] as 0600 where POSIX permissions exist; warns elsewhere. */
    fun writePrivate(ctx: CliContext, dir: File, file: File, content: String) {
        try {
            if (!dir.exists()) {
                Files.createDirectories(dir.toPath())
                posix(dir, "rwx------")
            }
            Files.write(file.toPath(), ByteArray(0))
            posix(file, "rw-------")
            file.writeText(content, Charsets.US_ASCII)
        } catch (e: java.io.IOException) {
            ioFailure("cannot write ${file.path}: ${e.message}", file.path)
        }
        if (!supportsPosix(file)) ctx.out.line("warning: cannot restrict permissions on ${file.path}; protect it yourself")
    }

    private fun decrypt(der: ByteArray, passphrase: CharArray, file: File): ByteArray = try {
        val (params, encrypted) = EncryptedKeyInfo.decode(der) ?: throw CliFailure(CliCode.KEY, "damaged encrypted key", ExitCode.VALIDATION, file.path)
        val cipher = Cipher.getInstance(PBE)
        val algParams = AlgorithmParameters.getInstance(PBE).apply { init(params) }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance(PBE).generateSecret(PBEKeySpec(passphrase)), algParams)
        cipher.doFinal(encrypted)
    } catch (e: GeneralSecurityException) {
        throw CliFailure(CliCode.KEY, "wrong passphrase or damaged key", ExitCode.VALIDATION, file.path)
    } catch (e: java.io.IOException) {
        throw CliFailure(CliCode.KEY, "damaged encrypted key", ExitCode.VALIDATION, file.path)
    }

    /**
     * PKCS#8 v1 Ed25519 keys carry only the 32-byte seed and the JDK has no seed-to-public API,
     * so the pair is regenerated from the seed: EdDSA key generation draws exactly those 32
     * bytes from its SecureRandom (RFC 8032 sec 5.1.5).
     */
    private fun publicOf(private: PrivateKey): PublicKey {
        val seed = (private as? EdECPrivateKey)?.bytes?.orElse(null)
            ?: throw CliFailure(CliCode.KEY, "not an Ed25519 private key", ExitCode.VALIDATION)
        val gen = KeyPairGenerator.getInstance("Ed25519")
        gen.initialize(NamedParameterSpec.ED25519, SeedRandom(seed))
        return gen.generateKeyPair().public
    }

    private class SeedRandom(private val seed: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            check(bytes.size == seed.size) { "unexpected key generation request" }
            seed.copyInto(bytes)
        }
    }

    private fun pem(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    private fun unpem(text: String): Pair<String, ByteArray>? {
        val m = Regex("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----").find(text) ?: return null
        val der = runCatching { Base64.getMimeDecoder().decode(m.groupValues[2]) }.getOrNull() ?: return null
        return m.groupValues[1] to der
    }

    private fun supportsPosix(f: File) = "posix" in f.toPath().fileSystem.supportedFileAttributeViews()

    private fun posix(f: File, perms: String) {
        if (supportsPosix(f)) Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(perms))
    }
}

/**
 * PKCS#8 `EncryptedPrivateKeyInfo` with a PBES2 algorithm identifier (RFC 5958, RFC 8018).
 * Written by hand because `javax.crypto.EncryptedPrivateKeyInfo` cannot name PBES2 parameters
 * on every JDK 17+; the parameters themselves come from the JDK cipher.
 */
internal object EncryptedKeyInfo {
    /** OID 1.2.840.113549.1.5.13 (id-PBES2), DER-encoded with its tag and length. */
    private val PBES2_OID = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0d)

    fun encode(pbes2Params: ByteArray, encrypted: ByteArray): ByteArray =
        tlv(0x30, tlv(0x30, PBES2_OID + pbes2Params) + tlv(0x04, encrypted))

    /** (PBES2 parameters DER, encrypted bytes), or null for anything else. */
    fun decode(der: ByteArray): Pair<ByteArray, ByteArray>? = runCatching {
        val outer = read(der, 0).takeIf { it.tag == 0x30 && it.end == der.size } ?: return null
        val alg = read(der, outer.start).takeIf { it.tag == 0x30 } ?: return null
        val oid = der.copyOfRange(alg.start, alg.start + PBES2_OID.size)
        if (!oid.contentEquals(PBES2_OID)) return null
        val params = der.copyOfRange(alg.start + PBES2_OID.size, alg.end)
        val data = read(der, alg.end).takeIf { it.tag == 0x04 && it.end == outer.end } ?: return null
        params to der.copyOfRange(data.start, data.end)
    }.getOrNull()

    private class Tlv(val tag: Int, val start: Int, val end: Int)

    private fun read(b: ByteArray, at: Int): Tlv {
        val tag = b[at].toInt() and 0xFF
        var i = at + 1
        var len = b[i++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            require(n in 1..3)
            len = 0
            repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xFF) }
        }
        require(i + len <= b.size)
        return Tlv(tag, i, i + len)
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }
}
