package dev.easyide.app.extensions.install

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import kotlinx.serialization.json.JsonArray
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
)

/** The version kept beside `current` for rollback, with what the user approved for it. */
data class RetainedVersion(val version: String, val approvedCapabilities: Set<String>)

/** `state.json` contents (registry-and-install.md sec 11.4), the subset local installs need. */
data class ExtensionState(val installs: List<InstallEntry>, val crashDisabled: Set<String>) {
    fun entryFor(id: String, scope: InstallScope, envId: String?): InstallEntry? =
        installs.firstOrNull { it.id == id && it.scope == scope && it.envId == envId }

    companion object { val EMPTY = ExtensionState(emptyList(), emptySet()) }
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
        return ExtensionState(installs, crash)
    }

    private fun decodeEntry(o: JsonObject): InstallEntry? {
        val id = o[KEY_ID]?.stringOrNull ?: return null
        val scope = o[KEY_SCOPE]?.stringOrNull?.let(InstallScope::parse) ?: return null
        val source = o[KEY_SOURCE]?.stringOrNull?.let { s -> Source.entries.firstOrNull { it.name == s } } ?: return null
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
                p[KEY_VERSION]?.stringOrNull?.let { RetainedVersion(it, approvals(p)) }
            },
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
                    JsonObject(mapOf(KEY_VERSION to JsonPrimitive(p.version), KEY_APPROVED to encodeApprovals(p.approvedCapabilities)))
                } ?: JsonNull),
            ))
        }),
        KEY_CRASH_DISABLED to JsonArray(state.crashDisabled.sorted().map(::JsonPrimitive)),
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
        const val TMP_SUFFIX = ".tmp"
    }
}
