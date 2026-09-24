package dev.easyide.app.lsp.servers

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One server as the Language servers screen lists it. */
data class ServerRow(
    val key: String,
    /** The declaring extension; null for a server defined only in `lsp.servers`. */
    val extensionId: String?,
    val languages: Set<String>,
    val command: List<String>,
    val enabled: Boolean,
    /** Some layer's `lsp.servers` has an entry for this key. */
    val customized: Boolean,
    /** The layer being edited holds an entry for this key (so it can be reset or removed there). */
    val setInLayer: Boolean,
) {
    val isCustom: Boolean get() = extensionId == null
}

/** An `lsp.servers` entry that is ignored in whole ([reasons]) or in part ([badFields]). */
data class ServerIssue(val key: String, val reasons: Set<RejectReason>, val badFields: List<String>)

data class ServersView(val rows: List<ServerRow>, val issues: List<ServerIssue>) {
    companion object { val EMPTY = ServersView(emptyList(), emptyList()) }
}

/**
 * The Language servers screen's model (customization.md sec 12), pure: resolved servers of
 * one (environment, project) from the packs' declarations and the layered `lsp.servers`,
 * the entries that cannot become a server, and the edits the screen writes into one
 * layer's `lsp.servers` object (the other layers keep theirs; objects merge per server).
 */
object LanguageServerRows {

    /** [layerValue]: the edited layer's own `lsp.servers` object (raw, not merged). */
    fun build(declared: List<ServerDeclaration>, settings: SettingsSnapshot, layerValue: JsonObject?): ServersView {
        val merged = settings[LspSettingsSchema.servers] as? JsonObject ?: JsonObject(emptyMap())
        val result = ServerConfigMerge.merge(
            declared,
            ServerConfigMerge.parseOverrides(merged),
            ServerDefaults(
                memoryBudgetMb = settings[LspSettingsSchema.defaultMemoryBudgetMb],
                idleShutdownSec = settings[LspSettingsSchema.idleShutdownSec],
                startupTimeoutSec = settings[LspSettingsSchema.startupTimeoutSec],
            ),
            lspEnabled = settings[LspSettingsSchema.enabled],
        )
        val rows = result.servers.map { s ->
            ServerRow(
                key = s.config.serverId, extensionId = s.extensionId, languages = s.config.languages, command = s.config.command,
                enabled = s.config.enabled, customized = s.config.serverId in merged, setInLayer = layerValue?.containsKey(s.config.serverId) == true,
            )
        }
        val rejected = result.rejections.associateBy { it.key }
        val keys = LinkedHashSet<String>().apply { addAll(rejected.keys); addAll(merged.keys) }
        val issues = keys.mapNotNull { key ->
            val reasons = rejected[key]?.reasons.orEmpty()
            val bad = merged[key]?.let(ServerConfigMerge::fieldProblems).orEmpty()
            if (reasons.isEmpty() && bad.isEmpty()) null else ServerIssue(key, reasons, bad)
        }
        return ServersView(rows, issues)
    }

    /** The layer object with [key]'s entry replaced ([entry] non-null) or removed; null when nothing is left. */
    fun withEntry(layerValue: JsonObject?, key: String, entry: JsonObject?): JsonObject? {
        val out = LinkedHashMap(layerValue.orEmpty())
        if (entry == null || entry.isEmpty()) out.remove(key) else out[key] = entry
        return out.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    /**
     * The layer object after switching [key] on or off here: `enabled: false` is written;
     * switching on drops the field (a pack server is on by default) unless a lower layer
     * turned it off, which needs an explicit `enabled: true`.
     */
    fun withEnabled(layerValue: JsonObject?, key: String, enabled: Boolean, offBelow: Boolean): JsonObject? {
        val entry = LinkedHashMap((layerValue?.get(key) as? JsonObject).orEmpty())
        if (enabled && !offBelow) entry.remove(ENABLED) else entry[ENABLED] = JsonPrimitive(enabled)
        return withEntry(layerValue, key, JsonObject(entry))
    }

    /**
     * A custom server entry from the add/edit form: languages separated by commas or spaces,
     * the command one argument per line (so arguments may contain spaces). Null when either
     * is empty. Fields of [previous] other than these are kept (env, rootMarkers ...).
     */
    fun customEntry(languages: String, command: String, previous: JsonObject?): JsonObject? {
        val langs = languages.split(',', ' ', '\n', '\t').map(String::trim).filter(String::isNotEmpty).distinct()
        val argv = command.lines().map(String::trim).filter(String::isNotEmpty)
        if (langs.isEmpty() || argv.isEmpty()) return null
        val out = LinkedHashMap(previous.orEmpty())
        out[LANGUAGES] = JsonArray(langs.map(::JsonPrimitive))
        out[COMMAND] = JsonArray(argv.map(::JsonPrimitive))
        return JsonObject(out)
    }

    /** Custom server keys are free names; `<extId>/<id>` is how pack servers are addressed. */
    fun isValidCustomKey(key: String): Boolean = key.isNotBlank() && key.trim() == key && '/' !in key

    /** The form's text for an existing entry: languages comma-separated, one argument per line. */
    fun formText(entry: JsonElement?): Pair<String, String> {
        val o = entry as? JsonObject ?: return "" to ""
        fun strings(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return strings(LANGUAGES).joinToString(", ") to strings(COMMAND).joinToString("\n")
    }

    private const val ENABLED = "enabled"
    private const val LANGUAGES = "languages"
    private const val COMMAND = "command"
}
