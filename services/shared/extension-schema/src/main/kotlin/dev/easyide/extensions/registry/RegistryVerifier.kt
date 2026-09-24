package dev.easyide.extensions.registry

import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** A signed index that passed every check of sdk-reference "Client verification" step 1. */
class VerifiedIndex internal constructor(
    val registryId: String,
    val generatedAt: Instant,
    val minAppVersion: SemVer?,
    val entries: List<IndexEntry>,
    val revocations: Revocations,
) {
    /** Versions of [id], newest first. */
    fun versions(id: ExtensionId): List<IndexEntry> = entries.filter { it.id == id }.sortedByDescending { it.version }
}

sealed interface Verified<out T> {
    data class Ok<T>(val value: T) : Verified<T>
    data class Rejected(val reason: String) : Verified<Nothing>
}

/**
 * What decides whether registry data may be shown or installed (registry-and-install.md sec
 * 4-6). Pure: bytes and keys in, verdicts out; fetching, caching and persisting pins belong to
 * the caller. Every failure is a hard stop with its reason, never "install anyway".
 */
class RegistryVerifier(ed: Ed25519) {
    private val signatures = SignatureVerifier(ed)

    /**
     * Step 1: `index.json` and `revocations.json` each verify against the registry root key
     * through their `.sig` files, and neither is older than the last verified copy.
     */
    fun verifyIndex(
        registryId: String,
        rootKey: ByteArray,
        index: ByteArray,
        indexSig: ByteArray,
        revocations: ByteArray,
        revocationsSig: ByteArray,
        previous: VerifiedIndex?,
    ): Verified<VerifiedIndex> = guarded {
        val indexJson = signedFile("index.json", index, indexSig, rootKey)
        val revJson = signedFile("revocations.json", revocations, revocationsSig, rootKey)
        val doc = IndexDocument.parse(indexJson.asObject("index.json"))
        val rev = Revocations.parse(revJson.asObject("revocations.json"))
        if (previous != null) {
            if (doc.generatedAt.isBefore(previous.generatedAt)) return@guarded Verified.Rejected("index.json is older than the last verified copy (${doc.generatedAt} < ${previous.generatedAt})")
            if (rev.updatedAt.isBefore(previous.revocations.updatedAt)) return@guarded Verified.Rejected("revocations.json is older than the last verified copy")
        }
        Verified.Ok(VerifiedIndex(registryId, doc.generatedAt, doc.minAppVersion, doc.entries, rev))
    }

    /** Step 2, first half: `publishers/<p>.json` verifies against the root key. */
    fun verifyPublisher(rootKey: ByteArray, file: ByteArray, sig: ByteArray, expected: String): Verified<PublisherKeys> = guarded {
        val keys = PublisherKeys.parse(signedFile("publishers/$expected.json", file, sig, rootKey).asObject("publisher file"))
        if (keys.publisher != expected) Verified.Rejected("publisher file names '${keys.publisher}', expected '$expected'") else Verified.Ok(keys)
    }

    /**
     * Steps 2-3 for one entry: its signature verifies with an active, unrevoked key of its
     * publisher, and that key is the device's pin for the publisher or reached from it by a
     * chain of rotation records each signed by the previous key.
     *
     * @param pinned the keyId pinned for this publisher on this device, null on first contact.
     * @return the keyId to pin once the install commits.
     */
    fun trustEntry(entry: IndexEntry, publisher: PublisherKeys, revocations: Revocations, pinned: String?): Verified<String> {
        if (publisher.publisher != entry.publisher) return Verified.Rejected("${entry.id}: publisher file is for '${publisher.publisher}'")
        val keyId = entry.signature.keyId
        val key = publisher.key(keyId) ?: return Verified.Rejected("${entry.id}: signed by unknown key $keyId")
        if (key.status != KeyStatus.ACTIVE) return Verified.Rejected("${entry.id}: signing key $keyId is retired")
        revocations.keyRevoked(keyId)?.let { return Verified.Rejected("${entry.id}: signing key $keyId revoked (${it.reason})") }
        if (!signatures.verifyEntry(entry.raw, key.publicKey)) return Verified.Rejected("${entry.id} ${entry.version}: entry signature does not verify")
        revocations.reason(entry.id, entry.version, keyId)?.let { return Verified.Rejected("${entry.id} ${entry.version}: $it") }
        if (pinned == null || pinned == keyId) return Verified.Ok(keyId)
        return rotationChain(publisher, revocations, pinned, keyId)
    }

    /** Package integrity (step 4, first half): exact size and sha256 of the downloaded bytes. */
    fun checkBytes(entry: IndexEntry, bytes: ByteArray): Verified<Unit> = when {
        bytes.size.toLong() != entry.size -> Verified.Rejected("${entry.id}: downloaded ${bytes.size} bytes, index says ${entry.size}")
        Hex.sha256(bytes) != entry.sha256 -> Verified.Rejected("${entry.id}: sha256 mismatch")
        else -> Verified.Ok(Unit)
    }

    private fun rotationChain(publisher: PublisherKeys, revocations: Revocations, pinned: String, target: String): Verified<String> {
        var current = pinned
        val seen = HashSet<String>()
        while (current != target) {
            if (!seen.add(current)) return Verified.Rejected("publisher ${publisher.publisher}: rotation records form a cycle")
            val step = publisher.rotation.firstOrNull { it.from == current }
                ?: return Verified.Rejected("publisher ${publisher.publisher} key changed from $pinned to $target with no rotation record")
            val old = publisher.key(current) ?: return Verified.Rejected("publisher ${publisher.publisher}: rotation from unknown key $current")
            revocations.keyRevoked(current)?.let { return Verified.Rejected("publisher ${publisher.publisher}: rotation from revoked key $current") }
            val ok = signatures.verify(SignedBytes.rotation(publisher.publisher, step.from, step.to), Sig(current, Sig.ALG, step.sigByOld), old.publicKey)
            if (!ok) return Verified.Rejected("publisher ${publisher.publisher}: rotation $current -> ${step.to} is not signed by $current")
            current = step.to
        }
        return Verified.Ok(target)
    }

    /** A JSON file and its `.sig` over the canonical bytes of the whole file. */
    private fun signedFile(name: String, bytes: ByteArray, sigBytes: ByteArray, rootKey: ByteArray): JsonElement {
        val json = Jcs.parse(String(bytes, Charsets.UTF_8))
        val sig = Sig.fromJson(Jcs.parse(String(sigBytes, Charsets.UTF_8))) ?: throw RegistryFormatException("$name.sig is not {keyId, alg: ed25519, sig}")
        if (!signatures.verify(SignedBytes.file(json), sig, rootKey)) throw RegistryFormatException("$name: signature does not verify against the registry root key")
        return json
    }

    /** Untrusted input boundary: any malformed or unverifiable document becomes a rejection. */
    private inline fun <T> guarded(block: () -> Verified<T>): Verified<T> = try {
        block()
    } catch (e: RegistryFormatException) {
        Verified.Rejected(e.message ?: "malformed registry document")
    } catch (e: CanonicalJsonException) {
        Verified.Rejected("not canonical-safe JSON: ${e.message}")
    }
}
