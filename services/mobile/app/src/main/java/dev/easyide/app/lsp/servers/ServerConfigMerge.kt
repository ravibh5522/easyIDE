package dev.easyide.app.lsp.servers

import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.FeatureFilter
import dev.easyide.lsp.session.ServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One `lsp.servers.<key>` entry: every field optional, so it can override a contributed
 * server field by field or, complete with `languages` and `command`, define a new one.
 */
data class ServerOverride(
    val languages: Set<String>? = null,
    val command: List<String>? = null,
    val env: Map<String, String>? = null,
    val initializationOptions: JsonElement? = null,
    val settingsSection: String? = null,
    val rootMarkers: List<String>? = null,
    val memoryBudgetMb: Int? = null,
    val idleShutdownSec: Int? = null,
    val startupTimeoutSec: Int? = null,
    val features: FeatureFilter? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
)

/** Why an `lsp.servers` entry (or a declared server after its override) cannot start. */
enum class RejectReason { NO_LANGUAGES, NO_COMMAND }

/** One entry that could not become a server; the Language servers screen shows it. */
data class ServerRejection(val key: String, val reasons: Set<RejectReason>)

/** The resolved list plus entries that could not become a server, shown as diagnostics. */
data class MergeResult(val servers: List<ResolvedServer>, val rejections: List<ServerRejection>) {
    val rejected: List<String> get() = rejections.map { it.key }
}

/**
 * `lsp.servers` parsing and the layering of sdk-reference: user entries beat extension
 * values, `enabled: false` (or `lsp.enabled: false`) disables, a key nobody declared adds a
 * server with no extension. Pure, so every rule is a unit test.
 */
object ServerConfigMerge {

    /**
     * Parses the setting's object. The user typed it, so a field of the wrong type is
     * dropped (that field only) rather than failing the whole entry.
     */
    fun parseOverrides(setting: JsonElement): Map<String, ServerOverride> {
        val root = setting as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, ServerOverride>()
        for ((key, value) in root) {
            val o = value as? JsonObject ?: continue
            out[key] = ServerOverride(
                languages = o.strings("languages")?.toSet(),
                command = o.strings("command"),
                env = (o["env"] as? JsonObject)?.let { env ->
                    env.mapNotNull { (k, v) -> v.stringOrNull()?.let { k to it } }.toMap()
                },
                initializationOptions = o["initializationOptions"]?.takeIf { it is JsonObject },
                settingsSection = o["settingsSection"]?.stringOrNull(),
                rootMarkers = o.strings("rootMarkers"),
                memoryBudgetMb = o.positiveInt("memoryBudgetMb"),
                idleShutdownSec = o.positiveInt("idleShutdownSec"),
                startupTimeoutSec = o.positiveInt("startupTimeoutSec"),
                features = (o["features"] as? JsonObject)?.let(::features),
                priority = (o["priority"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
                enabled = (o["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            )
        }
        return out
    }

    /**
     * [declared] servers in their order with any override applied, then user-only servers in
     * setting order. A user-only key needs `languages` and `command`; one without is
     * rejected (it is most often an override for a pack not installed in this environment).
     */
    fun merge(
        declared: List<ServerDeclaration>,
        overrides: Map<String, ServerOverride>,
        defaults: ServerDefaults,
        lspEnabled: Boolean,
    ): MergeResult {
        val servers = ArrayList<ResolvedServer>()
        val rejected = ArrayList<ServerRejection>()
        val seen = HashSet<String>()
        for (d in declared) {
            if (!seen.add(d.key)) continue
            val resolved = resolve(d, overrides[d.key], defaults, lspEnabled)
            if (resolved == null) rejected += ServerRejection(d.key, setOf(RejectReason.NO_COMMAND)) else servers += resolved
        }
        for ((key, o) in overrides) {
            if (key in seen) continue
            val languages = o.languages.orEmpty()
            val command = o.command.orEmpty()
            if (languages.isEmpty() || command.isEmpty()) {
                val reasons = buildSet {
                    if (languages.isEmpty()) add(RejectReason.NO_LANGUAGES)
                    if (command.isEmpty()) add(RejectReason.NO_COMMAND)
                }
                rejected += ServerRejection(key, reasons)
                continue
            }
            val base = ServerDeclaration(key = key, languages = languages, command = command)
            servers += resolve(base, o, defaults, lspEnabled) ?: continue
        }
        return MergeResult(servers, rejected)
    }

    private fun resolve(d: ServerDeclaration, o: ServerOverride?, defaults: ServerDefaults, lspEnabled: Boolean): ResolvedServer? {
        val command = o?.command ?: d.command
        if (command.isEmpty()) return null
        val config = ServerConfig(
            serverId = d.key,
            languages = o?.languages ?: d.languages,
            command = command,
            env = o?.env ?: d.env,
            initializationOptions = o?.initializationOptions ?: d.initializationOptions,
            settingsSection = o?.settingsSection ?: d.settingsSection,
            rootMarkers = o?.rootMarkers ?: d.rootMarkers,
            memoryBudgetMb = o?.memoryBudgetMb ?: d.memoryBudgetMb ?: defaults.memoryBudgetMb,
            idleShutdownSec = o?.idleShutdownSec ?: d.idleShutdownSec ?: defaults.idleShutdownSec,
            startupTimeoutSec = o?.startupTimeoutSec ?: d.startupTimeoutSec ?: defaults.startupTimeoutSec,
            features = o?.features ?: d.features,
            priority = o?.priority ?: d.priority,
            enabled = lspEnabled && (o?.enabled ?: true),
        )
        return ResolvedServer(config, d.install, d.extensionId)
    }

    /**
     * Fields of one `lsp.servers` entry whose value has the wrong type and so is ignored by
     * [parseOverrides] (the rest of the entry still applies); unknown fields are listed too.
     */
    fun fieldProblems(entry: JsonElement): List<String> {
        val o = entry as? JsonObject ?: return listOf(ENTRY)
        return o.entries.filter { (field, v) -> !fieldValid(field, v) }.map { it.key }
    }

    private fun fieldValid(field: String, v: JsonElement): Boolean = when (field) {
        "languages", "command", "rootMarkers" -> v is JsonArray && v.all { it.stringOrNull() != null }
        "env" -> v is JsonObject && v.values.all { it.stringOrNull() != null }
        "initializationOptions" -> v is JsonObject
        "settingsSection" -> v.stringOrNull() != null
        "memoryBudgetMb", "idleShutdownSec", "startupTimeoutSec" ->
            (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { it > 0 } == true
        "features" -> v is JsonObject && v.keys.all { it == "only" || it == "exclude" }
        "priority" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != null
        "enabled" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
        else -> false
    }

    /** What [fieldProblems] reports for an entry that is not an object at all. */
    const val ENTRY = "(entry)"

    /** `{only?, exclude?}` by sdk-reference feature id; unknown ids are ignored. */
    fun features(o: JsonObject): FeatureFilter {
        val byId = LspFeature.entries.associateBy { it.id }
        val only = o.strings("only")?.mapNotNull(byId::get)?.toSet()
        val exclude = o.strings("exclude")?.mapNotNull(byId::get)?.toSet().orEmpty()
        return FeatureFilter(only, exclude)
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { it.stringOrNull() }

    private fun JsonObject.positiveInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
}
