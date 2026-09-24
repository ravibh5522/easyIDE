package dev.easyide.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * The exec-bearing part of a project layer (LLD sec 12): values that make the
 * app spawn something. The project file arrives with `git clone` and sandbox
 * processes can write it, and a language server starts without any user
 * action - so these values apply only after explicit consent.
 */
object ExecBearing {

    /**
     * Just the exec-bearing values, shaped like a settings object (`[lang]`
     * blocks included): `lsp.servers.<k>.{command, env, initializationOptions}`
     * plus every declared key marked [Setting.execBearing]. Empty when none.
     */
    fun extract(doc: LayerDoc, schema: SchemaState): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        pick(doc.plain, schema).takeIf { it.isNotEmpty() }?.let(out::putAll)
        for ((lang, block) in doc.lang) {
            val picked = pick(block, schema)
            if (picked.isNotEmpty()) out["[$lang]"] = JsonObject(picked)
        }
        return JsonObject(out)
    }

    /** The layer without those values: what applies while the project is not trusted. */
    fun strip(doc: LayerDoc, schema: SchemaState): LayerDoc = LayerDoc(
        version = doc.version,
        plain = drop(doc.plain, schema),
        lang = doc.lang.mapValues { (_, b) -> drop(b, schema) }.filterValues { it.isNotEmpty() },
        errors = doc.errors,
    )

    /** sha256 of the canonical JSON of [extract]; null when there is nothing to trust. */
    fun fingerprint(extracted: JsonObject): String? = extracted.takeIf { it.isNotEmpty() }?.let(CanonicalJson::sha256)

    /** One line per affected server or key, showing exactly what would run (the consent sheet's content). */
    fun items(extracted: JsonObject): List<TrustItem> = buildList {
        fun addAll(values: JsonObject, language: String?) {
            for ((key, value) in values) {
                if (LayerDoc.isLanguageKey(key)) continue
                val servers = value as? JsonObject
                if (key == SettingsPolicy.LSP_SERVERS_KEY && servers != null) {
                    servers.forEach { (server, fields) ->
                        val f = (fields as? JsonObject)?.map { (n, v) -> n to v.toString() }.orEmpty()
                        add(TrustItem(server, language, f, isServer = true))
                    }
                } else {
                    add(TrustItem(key, language, listOf(key to value.toString()), isServer = false))
                }
            }
        }
        addAll(extracted, null)
        extracted.filterKeys(LayerDoc::isLanguageKey).forEach { (k, v) ->
            (v as? JsonObject)?.let { block -> LayerDoc.languagesOf(k).forEach { addAll(block, it) } }
        }
    }

    private fun pick(values: Map<String, JsonElement>, schema: SchemaState): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in values) {
            if (schema.byKey[key]?.execBearing == true) { out[key] = value; continue }
            if (key != SettingsPolicy.LSP_SERVERS_KEY || value !is JsonObject) continue
            val servers = value.mapNotNull { (server, entry) ->
                val fields = (entry as? JsonObject)?.filterKeys { it in SettingsPolicy.LSP_EXEC_FIELDS }.orEmpty()
                if (fields.isEmpty()) null else server to JsonObject(fields)
            }.toMap()
            if (servers.isNotEmpty()) out[key] = JsonObject(servers)
        }
        return out
    }

    private fun drop(values: Map<String, JsonElement>, schema: SchemaState): Map<String, JsonElement> =
        values.mapNotNull { (key, value) ->
            when {
                schema.byKey[key]?.execBearing == true -> null
                key == SettingsPolicy.LSP_SERVERS_KEY && value is JsonObject -> key to JsonObject(
                    value.mapValues { (_, entry) ->
                        (entry as? JsonObject)?.let { e -> JsonObject(e.filterKeys { it !in SettingsPolicy.LSP_EXEC_FIELDS }) } ?: entry
                    },
                )
                else -> key to value
            }
        }.toMap()
}

/** [fields] are (name, exact JSON) pairs: argv and env are shown verbatim, never summarised. */
data class TrustItem(val key: String, val language: String?, val fields: List<Pair<String, String>>, val isServer: Boolean)

enum class TrustState {
    /** The project layer holds no exec-bearing values. */
    NOT_REQUIRED,
    TRUSTED,
    /** "Never" for exactly this fingerprint. */
    DENIED,
    /** "Not now": blocked for this process, asked again next launch. */
    DEFERRED,
    /** Not decided: the consent sheet should be shown. */
    PENDING;

    val allowsExec: Boolean get() = this == TRUSTED
}

/** What the consent sheet needs; [state] PENDING means it should be shown now. */
data class TrustRequest(val projectId: String, val fingerprint: String, val items: List<TrustItem>, val state: TrustState)

/**
 * Consent records for project exec-bearing values, keyed by fingerprint so any
 * change to what would run re-prompts while reformatting does not. Stored as
 * DataStore `trust:lsp:<projectId>` = `<fp>` or `denied:<fp>` (LLD 4.1); never
 * exported, so trust does not travel to another device with a settings bundle.
 */
class ProjectTrust(private val dataStore: DataStore<Preferences>) {

    private val deferred = MutableStateFlow<Map<String, String>>(emptyMap())

    private val stored: Flow<Preferences> = dataStore.data.catch { cause ->
        // Boundary: unreadable preferences mean "not trusted", the safe reading.
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    fun state(projectId: String, fingerprint: String?): Flow<TrustState> =
        combine(stored.map { it[keyOf(projectId)] }, deferred.map { it[projectId] }) { record, later ->
            when {
                fingerprint == null -> TrustState.NOT_REQUIRED
                record == fingerprint -> TrustState.TRUSTED
                record == DENIED_PREFIX + fingerprint -> TrustState.DENIED
                later == fingerprint -> TrustState.DEFERRED
                else -> TrustState.PENDING
            }
        }.distinctUntilChanged()

    /** "Allow for this project". */
    suspend fun allow(projectId: String, fingerprint: String): Result<Unit> = record(projectId, fingerprint)

    /** "Never" - for this fingerprint; a changed file asks again. */
    suspend fun deny(projectId: String, fingerprint: String): Result<Unit> = record(projectId, DENIED_PREFIX + fingerprint)

    /** "Not now". */
    fun defer(projectId: String, fingerprint: String) {
        deferred.update { it + (projectId to fingerprint) }
    }

    /** Forget the decision, so the sheet asks again (the status item's "review"). */
    suspend fun reset(projectId: String): Result<Unit> {
        deferred.update { it - projectId }
        return runCatching { dataStore.edit { it.remove(keyOf(projectId)) } }
    }

    private suspend fun record(projectId: String, value: String): Result<Unit> {
        deferred.update { it - projectId }
        return runCatching { dataStore.edit { it[keyOf(projectId)] = value } }
    }

    private fun keyOf(projectId: String) = stringPreferencesKey(PREFIX + projectId)

    companion object {
        const val PREFIX = "trust:lsp:"
        private const val DENIED_PREFIX = "denied:"
    }
}
