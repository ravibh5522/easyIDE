package dev.easyide.extensions.registry

import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Layer
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.manifest.SemVerRange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** A registry document that does not have the sdk-reference shape; the whole document is refused. */
class RegistryFormatException(message: String) : IllegalArgumentException(message)

/** One element of `index.json` `extensions` (sdk-reference "Registry index format"). */
data class IndexEntry(
    val id: ExtensionId,
    val version: SemVer,
    val displayName: String?,
    val description: String?,
    val categories: List<String>,
    val license: String,
    val memoryBudgetMb: Int?,
    val engines: SemVerRange,
    val scope: InstallScope,
    val layers: Set<Layer>,
    val capabilities: List<String>,
    val url: String,
    val size: Long,
    val sha256: String,
    val publishedAt: Instant,
    val signature: Sig,
    /** The entry as published: what the signature covers. */
    val raw: JsonObject,
) {
    val publisher: String get() = id.publisher

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun parse(o: JsonObject): IndexEntry {
            val publisher = o.req("publisher")
            val name = o.req("name")
            val id = ExtensionId.of(publisher, name) ?: bad("invalid publisher/name '$publisher.$name'")
            o.str("id")?.let { if (!it.equals(id.value, ignoreCase = true)) bad("id '$it' does not match publisher.name '${id.value}'") }
            val url = o.req("url")
            if (!url.startsWith("https://")) bad("$id: url must be https")
            val sha = o.req("sha256").lowercase()
            if (!SHA256.matches(sha)) bad("$id: sha256 must be 64 hex chars")
            val size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: bad("$id: size must be a positive integer")
            val engines = o.obj("engines")?.str("easyide")?.let(SemVerRange::parse) ?: bad("$id: engines.easyide missing or not a range")
            return IndexEntry(
                id = id,
                version = SemVer.parse(o.req("version")) ?: bad("$id: version is not SemVer"),
                displayName = o.str("displayName"),
                description = o.str("description"),
                categories = o.strs("categories"),
                license = o.req("license"),
                memoryBudgetMb = o["memoryBudgetMb"]?.intOrNull,
                engines = engines,
                scope = InstallScope.parse(o.req("scope")) ?: bad("$id: scope must be global or environment"),
                layers = o.strs("layers").map { l -> Layer.entries.firstOrNull { it.name == l } ?: bad("$id: unknown layer '$l'") }.toSet(),
                capabilities = o.strs("capabilities"),
                url = url,
                size = size,
                sha256 = sha,
                publishedAt = instant(o.req("publishedAt"), "$id publishedAt"),
                signature = Sig.fromJson(o["signature"]) ?: bad("$id: signature missing or malformed"),
                raw = o,
            )
        }
    }
}

enum class KeyStatus { ACTIVE, RETIRED }

data class PubKey(val keyId: String, val publicKey: ByteArray, val added: LocalDate?, val status: KeyStatus) {
    override fun equals(other: Any?) = other is PubKey && keyId == other.keyId && publicKey.contentEquals(other.publicKey) && added == other.added && status == other.status
    override fun hashCode() = keyId.hashCode()
}

data class Rotation(val from: String, val to: String, val sigByOld: ByteArray) {
    override fun equals(other: Any?) = other is Rotation && from == other.from && to == other.to && sigByOld.contentEquals(other.sigByOld)
    override fun hashCode() = from.hashCode() * 31 + to.hashCode()
}

/** `publishers/<publisher>.json`. */
data class PublisherKeys(val publisher: String, val displayName: String?, val keys: List<PubKey>, val rotation: List<Rotation>) {
    fun key(keyId: String): PubKey? = keys.firstOrNull { it.keyId == keyId }

    companion object {
        fun parse(o: JsonObject): PublisherKeys {
            val publisher = o.req("publisher")
            if (!ExtensionId.SEGMENT.matches(publisher)) bad("invalid publisher '$publisher'")
            val keys = o.objs("keys").map { k ->
                val raw = k.req("publicKey").let(::decodeBase64)?.takeIf { it.size == KeyIds.RAW_KEY_BYTES } ?: bad("$publisher: publicKey must be base64 of 32 bytes")
                val keyId = k.req("keyId")
                if (keyId != KeyIds.of(raw)) bad("$publisher: keyId $keyId does not match its key")
                val status = when (k.str("status") ?: "active") {
                    "active" -> KeyStatus.ACTIVE
                    "retired" -> KeyStatus.RETIRED
                    else -> bad("$publisher: key status must be active or retired")
                }
                PubKey(keyId, raw, k.str("added")?.let { date(it, "$publisher key added") }, status)
            }
            val rotation = o.objs("rotation").map { r ->
                Rotation(r.req("from"), r.req("to"), r.req("sigByOld").let(::decodeBase64) ?: bad("$publisher: sigByOld is not base64"))
            }
            return PublisherKeys(publisher, o.str("displayName"), keys, rotation)
        }
    }
}

data class RevokedKey(val keyId: String, val reason: String, val since: LocalDate?)

data class RevokedRange(val id: ExtensionId, val versions: SemVerRange, val reason: String)

/** `revocations.json`. */
data class Revocations(val updatedAt: Instant, val keys: List<RevokedKey>, val versions: List<RevokedRange>) {
    fun keyRevoked(keyId: String): RevokedKey? = keys.firstOrNull { it.keyId == keyId }

    /** Why [id] [version] (signed by [signedBy], null for unsigned) must not be installed or stay enabled; null when it may. */
    fun reason(id: ExtensionId, version: SemVer, signedBy: String?): String? =
        signedBy?.let(::keyRevoked)?.let { "signing key ${it.keyId} revoked: ${it.reason}" }
            ?: versions.firstOrNull { it.id == id && it.versions.contains(version) }?.let { "version revoked: ${it.reason}" }

    companion object {
        val NONE = Revocations(Instant.EPOCH, emptyList(), emptyList())

        fun parse(o: JsonObject): Revocations {
            schema(o)
            return Revocations(
                updatedAt = instant(o.req("updatedAt"), "revocations updatedAt"),
                keys = o.objs("keys").map { RevokedKey(it.req("keyId"), it.str("reason").orEmpty(), it.str("since")?.let { d -> date(d, "revoked key since") }) },
                versions = o.objs("versions").map { v ->
                    RevokedRange(
                        ExtensionId.parse(v.req("id")) ?: bad("revoked id '${v.req("id")}' is not publisher.name"),
                        SemVerRange.parse(v.req("versions")) ?: bad("revoked versions '${v.req("versions")}' is not a range"),
                        v.str("reason").orEmpty(),
                    )
                },
            )
        }
    }
}

/** `index.json` before its signature has been checked; only [RegistryVerifier] turns it into a [VerifiedIndex]. */
internal data class IndexDocument(val generatedAt: Instant, val minAppVersion: SemVer?, val entries: List<IndexEntry>) {
    companion object {
        fun parse(o: JsonObject): IndexDocument {
            schema(o)
            val entries = o.arr("extensions").map { IndexEntry.parse(it as? JsonObject ?: bad("extensions[] must be objects")) }
            val dup = entries.groupBy { it.id to it.version }.filterValues { it.size > 1 }.keys.firstOrNull()
            if (dup != null) bad("duplicate entry ${dup.first} ${dup.second}")
            return IndexDocument(
                instant(o.req("generatedAt"), "generatedAt"),
                o.str("minAppVersion")?.let { SemVer.parse(it) ?: bad("minAppVersion is not SemVer") },
                entries,
            )
        }
    }
}

// --- strict field access: any shape error refuses the document ---------------------------

internal const val INDEX_SCHEMA_VERSION = 1

private fun schema(o: JsonObject) {
    val v = o["schemaVersion"]?.intOrNull ?: bad("schemaVersion missing")
    if (v != INDEX_SCHEMA_VERSION) bad("unsupported schemaVersion $v")
}

internal fun bad(message: String): Nothing = throw RegistryFormatException(message)

private fun JsonObject.str(k: String): String? = this[k]?.let { it.stringOrNull ?: bad("'$k' must be a string") }
private fun JsonObject.req(k: String): String = str(k) ?: bad("'$k' is required")
private fun JsonObject.obj(k: String): JsonObject? = this[k]?.let { it as? JsonObject ?: bad("'$k' must be an object") }
private fun JsonObject.arr(k: String): JsonArray = this[k]?.let { it as? JsonArray ?: bad("'$k' must be an array") } ?: JsonArray(emptyList())
private fun JsonObject.objs(k: String): List<JsonObject> = arr(k).map { it as? JsonObject ?: bad("'$k' items must be objects") }
private fun JsonObject.strs(k: String): List<String> = arr(k).map { it.stringOrNull ?: bad("'$k' items must be strings") }

private fun instant(s: String, what: String): Instant = try { Instant.parse(s) } catch (e: DateTimeParseException) { bad("$what '$s' is not an ISO-8601 instant") }
private fun date(s: String, what: String): LocalDate = try { LocalDate.parse(s) } catch (e: DateTimeParseException) { bad("$what '$s' is not a date") }

internal fun JsonElement.asObject(what: String): JsonObject = this as? JsonObject ?: bad("$what must be a JSON object")
