package dev.easyide.app.extensions.install

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One installed version as the installer recorded it. [approvedCapabilities] is the exact
 * set the user approved on the capability sheet (ECO-03); a later version needing more is
 * disabled until re-approved (enablement rule 5). [previous] is the retained version a
 * rollback flips back to (registry-and-install.md sec 10, sec 11.4).
 */
data class InstallEntry(
    val id: String,
    val scope: InstallScope,
    val envId: String?,
    val source: Source,
    val version: String,
    val installedAt: Long,
    val approvedCapabilities: Set<String>,
    val previous: RetainedVersion? = null,
    /** Where a [Source.REGISTRY] install came from and who signed it; null for local installs. */
    val origin: RegistryOrigin? = null,
)

/** The version kept beside `current` for rollback, with what the user approved for it. */
data class RetainedVersion(
    val version: String,
    val approvedCapabilities: Set<String>,
    /** Null in files written before registry installs existed: the entry's own source applies. */
    val source: Source? = null,
    val origin: RegistryOrigin? = null,
)

/** A registry install's provenance (`InstallRecord` registryId/sha256/signedBy, registry-and-install.md sec 2). */
data class RegistryOrigin(val registryId: String, val signedBy: String, val sha256: String)

/**
 * `state.json` contents (registry-and-install.md sec 11.4). [pins] is TOFU trust,
 * `{registryId: {publisher: keyId}}` (sec 5); [revoked] is `{registryId: {"id@version": reason}}`,
 * recomputed for a registry after each of its refreshes (sec 6).
 */
data class ExtensionState(
    val installs: List<InstallEntry>,
    val crashDisabled: Set<String>,
    val pins: Map<String, Map<String, String>> = emptyMap(),
    val revoked: Map<String, Map<String, String>> = emptyMap(),
) {
    fun entryFor(id: String, scope: InstallScope, envId: String?): InstallEntry? =
        installs.firstOrNull { it.id == id && it.scope == scope && it.envId == envId }

    /** Why [id] [version] is revoked by any registry; null when it is not. */
    fun revocationReason(id: String, version: String): String? = revoked.values.firstNotNullOfOrNull { it[revokedKey(id, version)] }

    companion object {
        val EMPTY = ExtensionState(emptyList(), emptySet())

        fun revokedKey(id: String, version: String) = "$id@$version"
    }
}

/**
 * Reads and writes `<files>/extensions/state.json`: approvals, install metadata and
 * crash-disables. Enablement by the user is not here; that is `extensions.disabled`.
 *
 * I/O boundary: an unreadable or corrupt file reads as empty (every package then needs
 * approval again, which is the safe direction); writes are temp + fsync + atomic rename so
 * a crash mid-write keeps the previous state. Thread-safe.
 */
class ExtensionStateStore(private val file: File) {

    private val lock = Any()

    fun read(): ExtensionState = synchronized(lock) { load() }

    /** Applies [transform] to the current state and persists the result. */
    fun update(transform: (ExtensionState) -> ExtensionState): ExtensionState = synchronized(lock) {
        val next = transform(load())
        persist(next)
        next
    }

    private fun load(): ExtensionState {
        if (!file.exists()) return ExtensionState.EMPTY
        val text = try { file.readText() } catch (e: IOException) { return ExtensionState.EMPTY }
        val root = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject ?: return ExtensionState.EMPTY
        val installs = (root[KEY_INSTALLS] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeEntry) }
        val crash = (root[KEY_CRASH_DISABLED] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { it.stringOrNull }
        return ExtensionState(installs, crash, nested(root[KEY_PINS]), nested(root[KEY_REVOKED]))
    }

    /** `{a: {b: "c"}}`; malformed members are dropped (absent pins re-pin on the next install). */
    private fun nested(v: JsonElement?): Map<String, Map<String, String>> =
        (v as? JsonObject).orEmpty().mapNotNull { (k, inner) ->
            (inner as? JsonObject)?.let { o -> k to o.mapNotNull { (ik, iv) -> iv.stringOrNull?.let { ik to it } }.toMap() }
        }.toMap()

    private fun encodeNested(m: Map<String, Map<String, String>>) = JsonObject(
        m.toSortedMap().mapValues { (_, inner) -> JsonObject(inner.toSortedMap().mapValues { JsonPrimitive(it.value) }) },
    )

    private fun decodeOrigin(v: JsonElement?): RegistryOrigin? {
        val o = v as? JsonObject ?: return null
        return RegistryOrigin(
            o[KEY_REGISTRY]?.stringOrNull ?: return null,
            o[KEY_SIGNED_BY]?.stringOrNull ?: return null,
            o[KEY_SHA256]?.stringOrNull ?: return null,
        )
    }

    private fun encodeOrigin(o: RegistryOrigin?) = o?.let {
        JsonObject(mapOf(KEY_REGISTRY to JsonPrimitive(it.registryId), KEY_SIGNED_BY to JsonPrimitive(it.signedBy), KEY_SHA256 to JsonPrimitive(it.sha256)))
    } ?: JsonNull

    private fun decodeSource(v: JsonElement?): Source? =
        v?.stringOrNull?.let { s -> Source.entries.firstOrNull { it.name == s } }

    private fun decodeEntry(o: JsonObject): InstallEntry? {
        val id = o[KEY_ID]?.stringOrNull ?: return null
        val scope = o[KEY_SCOPE]?.stringOrNull?.let(InstallScope::parse) ?: return null
        val source = decodeSource(o[KEY_SOURCE]) ?: return null
        val version = o[KEY_VERSION]?.stringOrNull ?: return null
        return InstallEntry(
            id = id,
            scope = scope,
            envId = o[KEY_ENV_ID]?.stringOrNull,
            source = source,
            version = version,
            installedAt = (o[KEY_INSTALLED_AT] as? JsonPrimitive)?.longOrNull ?: 0L,
            approvedCapabilities = approvals(o),
            // Absent in files written before rollback existed: the entry then has no record.
            previous = (o[KEY_PREVIOUS] as? JsonObject)?.let { p ->
                p[KEY_VERSION]?.stringOrNull?.let { RetainedVersion(it, approvals(p), decodeSource(p[KEY_SOURCE]), decodeOrigin(p[KEY_ORIGIN])) }
            },
            origin = decodeOrigin(o[KEY_ORIGIN]),
        )
    }

    private fun approvals(o: JsonObject): Set<String> =
        (o[KEY_APPROVED] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { it.stringOrNull }

    private fun encodeApprovals(approved: Set<String>) = JsonArray(approved.sorted().map(::JsonPrimitive))

    private fun encode(state: ExtensionState): JsonObject = JsonObject(mapOf(
        KEY_SCHEMA_VERSION to JsonPrimitive(SCHEMA_VERSION),
        KEY_INSTALLS to JsonArray(state.installs.map { e ->
            JsonObject(mapOf(
                KEY_ID to JsonPrimitive(e.id),
                KEY_SCOPE to JsonPrimitive(e.scope.wire),
                KEY_ENV_ID to (e.envId?.let(::JsonPrimitive) ?: JsonNull),
                KEY_SOURCE to JsonPrimitive(e.source.name),
                KEY_VERSION to JsonPrimitive(e.version),
                KEY_INSTALLED_AT to JsonPrimitive(e.installedAt),
                KEY_APPROVED to encodeApprovals(e.approvedCapabilities),
                KEY_PREVIOUS to (e.previous?.let { p ->
                    JsonObject(mapOf(
                        KEY_VERSION to JsonPrimitive(p.version), KEY_APPROVED to encodeApprovals(p.approvedCapabilities),
                        KEY_SOURCE to (p.source?.let { JsonPrimitive(it.name) } ?: JsonNull), KEY_ORIGIN to encodeOrigin(p.origin),
                    ))
                } ?: JsonNull),
                KEY_ORIGIN to encodeOrigin(e.origin),
            ))
        }),
        KEY_CRASH_DISABLED to JsonArray(state.crashDisabled.sorted().map(::JsonPrimitive)),
        KEY_PINS to encodeNested(state.pins),
        KEY_REVOKED to encodeNested(state.revoked),
    ))

    /** @throws IOException when the state cannot be written; installs must not pretend to succeed. */
    private fun persist(state: ExtensionState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(encode(state).toString().toByteArray())
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "schemaVersion"
        const val KEY_INSTALLS = "installs"
        const val KEY_CRASH_DISABLED = "crashDisabled"
        const val KEY_ID = "id"
        const val KEY_SCOPE = "scope"
        const val KEY_ENV_ID = "envId"
        const val KEY_SOURCE = "source"
        const val KEY_VERSION = "version"
        const val KEY_INSTALLED_AT = "installedAt"
        const val KEY_APPROVED = "approvedCapabilities"
        const val KEY_PREVIOUS = "previous"
        const val KEY_ORIGIN = "origin"
        const val KEY_REGISTRY = "registryId"
        const val KEY_SIGNED_BY = "signedBy"
        const val KEY_SHA256 = "sha256"
        const val KEY_PINS = "pins"
        const val KEY_REVOKED = "revoked"
        const val TMP_SUFFIX = ".tmp"
    }
}
