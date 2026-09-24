package dev.easyide.extensions.host

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Verdict read at process start (extension-runtime.md sec 5.3). */
data class StartupVerdict(val suspects: List<ExtensionId>, val consecutive: Int, val enterSafeMode: Boolean)

/**
 * Detects the one failure nothing in-process can catch: the app dying while an extension
 * registers contributions or activates (OOM on a huge theme, a pathological grammar).
 * [begin] is persisted and fsynced before the step runs and [end] clears it, so a record
 * left at the next start names the extension the process died inside. After
 * [ExtensionPolicy.SAFE_MODE_AFTER_CONSECUTIVE_CRASHES] such starts in a row the verdict
 * asks for safe mode.
 *
 * The file is derived, best-effort state: an I/O failure is logged and the step proceeds,
 * because a broken journal must never block startup. Thread-safe.
 */
class CrashJournal(private val file: File, private val log: ExtensionLog) {

    enum class Phase { REGISTER_CONTRIBUTIONS, ACTIVATE }

    private val lock = Any()
    private val open = LinkedHashMap<ExtensionId, Phase>()
    private var consecutive = 0
    private val suspects = LinkedHashSet<ExtensionId>()

    /** Call once, before anything is registered. Records left over become suspects. */
    fun onStartup(): StartupVerdict = synchronized(lock) {
        val stored = load()
        val leftovers = stored?.open ?: emptyList()
        consecutive = (stored?.consecutive ?: 0) + if (leftovers.isNotEmpty()) 1 else 0
        suspects.clear()
        if (consecutive > 0) suspects += stored?.suspects.orEmpty()
        suspects += leftovers
        open.clear()
        persist()
        StartupVerdict(suspects.toList(), consecutive, consecutive >= ExtensionPolicy.SAFE_MODE_AFTER_CONSECUTIVE_CRASHES)
    }

    fun begin(id: ExtensionId, phase: Phase) = synchronized(lock) {
        open[id] = phase
        persist()
    }

    fun end(id: ExtensionId) = synchronized(lock) {
        if (open.remove(id) != null) persist()
    }

    /** At `onStartupFinished`: a run that got here with nothing open resets the counter. */
    fun markCleanRun() = synchronized(lock) {
        if (open.isNotEmpty() || (consecutive == 0 && suspects.isEmpty())) return@synchronized
        consecutive = 0
        suspects.clear()
        persist()
    }

    private class Stored(val consecutive: Int, val open: List<ExtensionId>, val suspects: List<ExtensionId>)

    /** I/O and parse boundary: an unreadable journal counts as empty. */
    private fun load(): Stored? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: IOException) {
            warn("cannot read ${file.name}: ${e.message}")
            return null
        }
        val obj = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject
        if (obj == null) {
            warn("${file.name} is corrupt; starting a fresh journal")
            return null
        }
        fun ids(key: String) = (obj[key] as? JsonArray)?.mapNotNull { e ->
            ((e as? JsonObject)?.get(KEY_ID) ?: e).stringOrNull?.let(ExtensionId::parse)
        } ?: emptyList()
        return Stored(obj[KEY_CONSECUTIVE]?.intOrNull?.coerceAtLeast(0) ?: 0, ids(KEY_OPEN), ids(KEY_SUSPECTS))
    }

    /** Write-to-temp, fsync, atomic rename: a crash mid-write leaves the previous journal intact. */
    private fun persist() {
        val json = JsonObject(mapOf(
            KEY_CONSECUTIVE to JsonPrimitive(consecutive),
            KEY_OPEN to JsonArray(open.map { (id, phase) -> JsonObject(mapOf(KEY_ID to JsonPrimitive(id.value), KEY_PHASE to JsonPrimitive(phase.name))) }),
            KEY_SUSPECTS to JsonArray(suspects.map { JsonPrimitive(it.value) }),
        ))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray())
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            warn("cannot write ${file.name}: ${e.message}")
        }
    }

    private fun warn(message: String) = log.append(LogEntry(null, LogLevel.WARN, "activation journal: $message"))

    private companion object {
        const val KEY_CONSECUTIVE = "consecutive"
        const val KEY_OPEN = "open"
        const val KEY_SUSPECTS = "suspects"
        const val KEY_ID = "id"
        const val KEY_PHASE = "phase"
        const val TMP_SUFFIX = ".tmp"
    }
}
