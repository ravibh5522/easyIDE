package dev.easyide.extensions.registry

import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/**
 * ed25519 primitive. The platform provider's availability on Android below API 33 is
 * unverified (registry-and-install.md sec 4.2), so callers inject it: the CLI uses the JDK,
 * tests use RFC 8032 vectors.
 */
interface Ed25519 {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** A detached signature: a `.sig` file, an entry's `signature` or a `.easyext.sig`. */
data class Sig(val keyId: String, val alg: String, val sig: ByteArray) {
    fun toJson(): JsonObject = JsonObject(
        mapOf("keyId" to JsonPrimitive(keyId), "alg" to JsonPrimitive(alg), "sig" to JsonPrimitive(Base64.getEncoder().encodeToString(sig))),
    )

    override fun equals(other: Any?) = other is Sig && keyId == other.keyId && alg == other.alg && sig.contentEquals(other.sig)
    override fun hashCode() = (keyId.hashCode() * 31 + alg.hashCode()) * 31 + sig.contentHashCode()

    companion object {
        const val ALG = "ed25519"

        /** Null for anything but `{"keyId","alg":"ed25519","sig":base64}`. */
        fun fromJson(v: JsonElement?): Sig? {
            val o = v as? JsonObject ?: return null
            val keyId = o["keyId"]?.stringOrNull?.takeIf(KeyIds::isValid) ?: return null
            val alg = o["alg"]?.stringOrNull?.takeIf { it == ALG } ?: return null
            val sig = o["sig"]?.stringOrNull?.let(::decodeBase64) ?: return null
            return Sig(keyId, alg, sig)
        }
    }
}

object KeyIds {
    private val PATTERN = Regex("^[0-9a-f]{16}$")

    /** First 16 hex chars of sha256 over the raw 32-byte public key. */
    fun of(rawPublicKey: ByteArray): String {
        require(rawPublicKey.size == RAW_KEY_BYTES) { "ed25519 public key must be $RAW_KEY_BYTES bytes" }
        return Hex.encode(MessageDigest.getInstance("SHA-256").digest(rawPublicKey)).substring(0, 16)
    }

    fun isValid(keyId: String): Boolean = PATTERN.matches(keyId)

    const val RAW_KEY_BYTES = 32
}

/** What each kind of signature covers (sdk-reference "Registry index format"). */
object SignedBytes {
    /** An index entry: Jcs of the entry without its `signature` member. */
    fun entry(entry: JsonObject): ByteArray = Jcs.canonicalize(JsonObject(entry - "signature"))

    /** A signed JSON file (`index.json`, `publishers/<p>.json`, `revocations.json`): Jcs of the whole file. */
    fun file(document: JsonElement): ByteArray = Jcs.canonicalize(document)

    /** A rotation record's `sigByOld`. */
    fun rotation(publisher: String, from: String, to: String): ByteArray = Jcs.canonicalize(
        JsonObject(mapOf("publisher" to JsonPrimitive(publisher), "from" to JsonPrimitive(from), "to" to JsonPrimitive(to))),
    )
}

class SignatureVerifier(private val ed: Ed25519) {
    /** [publicKey] is the raw 32-byte key; the signature's keyId must name it. */
    fun verify(signed: ByteArray, sig: Sig, publicKey: ByteArray): Boolean =
        sig.alg == Sig.ALG && publicKey.size == KeyIds.RAW_KEY_BYTES && sig.keyId == KeyIds.of(publicKey) &&
            ed.verify(publicKey, signed, sig.sig)

    /** An index entry's embedded `signature`. False when it is missing or malformed. */
    fun verifyEntry(entry: JsonObject, publicKey: ByteArray): Boolean {
        val sig = Sig.fromJson(entry["signature"]) ?: return false
        val bytes = try { SignedBytes.entry(entry) } catch (e: CanonicalJsonException) { return false }
        return verify(bytes, sig, publicKey)
    }

    /** A package's detached `.easyext.sig`, over the exact archive bytes. */
    fun verifyPackage(archive: ByteArray, sig: Sig, publicKey: ByteArray): Boolean = verify(archive, sig, publicKey)
}

object Hex {
    fun encode(b: ByteArray): String = buildString(b.size * 2) { for (x in b) append(String.format("%02x", x.toInt() and 0xFF)) }

    fun sha256(b: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(b))
}

internal fun decodeBase64(s: String): ByteArray? = try { Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { null }

/**
 * Producing signed registry documents: what `easyide-ext publish` and `registry build` write
 * and what tests use. [sign] holds the private key, so this code never sees it.
 */
object RegistrySigning {
    /** [entry] with its `signature` member set over the rest of it. */
    fun signEntry(entry: JsonObject, keyId: String, sign: (ByteArray) -> ByteArray): JsonObject {
        val unsigned = JsonObject(entry - "signature")
        return JsonObject(unsigned + ("signature" to Sig(keyId, Sig.ALG, sign(SignedBytes.entry(unsigned))).toJson()))
    }

    /** The `.sig` document for a signed JSON file. */
    fun fileSig(document: JsonElement, keyId: String, sign: (ByteArray) -> ByteArray): JsonObject =
        Sig(keyId, Sig.ALG, sign(SignedBytes.file(document))).toJson()
}
