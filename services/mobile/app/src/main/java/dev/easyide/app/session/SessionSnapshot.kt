package dev.easyide.app.session

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where an unsaved buffer's text is kept ([file], inside the project's backup directory) and
 * what it was edited from: [baseSha256] is the SHA-256 of the disk text at the time, so a
 * restore can tell "the file is as I left it" from "something else changed it meanwhile".
 */
data class BackupRef(val file: String, val baseSha256: String)

/** One open editor tab. [backup] is set only while the buffer holds unsaved edits. */
data class TabSnapshot(
    val path: String,
    val caretStart: Int = 0,
    val caretEnd: Int = 0,
    val scrollY: Int = 0,
    val scrollX: Int = 0,
    val showPreview: Boolean = false,
    val backup: BackupRef? = null,
)

/**
 * Everything about a workspace that survives the process. Terminals are not here: a shell
 * is a process and dies with the app, so a restore starts a fresh one.
 */
data class SessionSnapshot(
    val projectId: String,
    val savedAtMs: Long,
    val tabs: List<TabSnapshot>,
    val activePath: String?,
    val expandedDirs: List<String>,
    /** The workspace shell's own snapshot (panels, sizes, preset, pages), opaque here: `ShellSnapshot` reads and writes it. */
    val shell: String?,
) {
    /** Nothing worth restoring: an empty snapshot is not written, it clears the stored one. */
    val isEmpty: Boolean get() = tabs.isEmpty() && expandedDirs.isEmpty() && shell == null
}

/**
 * The on-disk JSON of a [SessionSnapshot].
 *
 * Versioning is additive-only: [VERSION] is written as `v`, a reader takes any `v >= 1`,
 * ignores keys it does not know and defaults keys it does not find. So a newer build's file
 * still restores in an older build (the fields it added are dropped), and an older file
 * restores in a newer one. A change that cannot be expressed that way must use a new file
 * name instead of reinterpreting these keys.
 */
object SessionSnapshotCodec {
    const val VERSION = 1

    private const val MIN_READABLE_VERSION = 1

    fun encode(snapshot: SessionSnapshot): String = buildJsonObject {
        put("v", VERSION)
        put("projectId", snapshot.projectId)
        put("savedAtMs", snapshot.savedAtMs)
        put("activePath", snapshot.activePath)
        put("tabs", buildJsonArray { snapshot.tabs.forEach { add(tabJson(it)) } })
        put("expandedDirs", buildJsonArray { snapshot.expandedDirs.forEach { add(JsonPrimitive(it)) } })
        snapshot.shell?.let { put("shell", it) }
    }.toString()

    /**
     * Null when [text] is not a snapshot this reader can use (not JSON, not an object, no
     * usable version or project id). The file is disk data another version may have written,
     * so a bad one must cost the restore, never the launch.
     */
    fun decode(text: String): SessionSnapshot? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val version = root.int("v") ?: return null
        val projectId = root.string("projectId") ?: return null
        if (version < MIN_READABLE_VERSION) return null
        return SessionSnapshot(
            projectId = projectId,
            savedAtMs = root.long("savedAtMs") ?: 0L,
            tabs = (root["tabs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::tabOf) },
            activePath = root.string("activePath"),
            expandedDirs = (root["expandedDirs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            shell = root.string("shell"),
        )
    }

    private fun tabJson(tab: TabSnapshot): JsonObject = buildJsonObject {
        put("path", tab.path)
        put("caretStart", tab.caretStart)
        put("caretEnd", tab.caretEnd)
        put("scrollY", tab.scrollY)
        put("scrollX", tab.scrollX)
        put("showPreview", tab.showPreview)
        tab.backup?.let { backup ->
            put("backup", buildJsonObject {
                put("file", backup.file)
                put("baseSha256", backup.baseSha256)
            })
        }
    }

    private fun tabOf(json: JsonObject): TabSnapshot? {
        val path = json.string("path")?.takeIf { it.isNotEmpty() } ?: return null
        val backup = (json["backup"] as? JsonObject)?.let { b ->
            val file = b.string("file")
            val base = b.string("baseSha256")
            if (file != null && base != null) BackupRef(file, base) else null
        }
        return TabSnapshot(
            path = path,
            caretStart = json.int("caretStart") ?: 0,
            caretEnd = json.int("caretEnd") ?: 0,
            scrollY = json.int("scrollY") ?: 0,
            scrollX = json.int("scrollX") ?: 0,
            showPreview = json.bool("showPreview") ?: false,
            backup = backup,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
