package dev.easyide.app.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/**
 * The default profile's USER layer on the app's one preferences DataStore
 * (shared with [dev.easyide.app.data.UiPreferences]; DataStore forbids two
 * instances on one file). Each key is `setting:<key>` holding compact JSON, and
 * each language block `setting:[lang]` holding a JSON object (LLD 4.1).
 */
class DataStoreUserLayer(private val dataStore: DataStore<Preferences>) : LayerSource {

    override val source: String = "profile ${SettingsPolicy.DEFAULT_PROFILE}"

    override val doc: Flow<LayerDoc> = dataStore.data
        .catch { cause ->
            // Boundary: an unreadable file means defaults, not a crash on launch.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::decode)
        // The file also holds app state and trust records; their writes must not
        // produce a new settings snapshot (and a recomposition) with equal values.
        .distinctUntilChangedBy { it.plain to it.lang }

    override suspend fun write(edits: List<SettingEdit>): Result<Unit> = runCatching {
        dataStore.edit { prefs -> edits.forEach { apply(prefs, it) } }
    }

    override suspend fun readText(): String = JsoncEditor.render(doc.first().toJson())

    override suspend fun writeText(text: String): Result<Unit> = when (val parsed = LayerDoc.parse(text)) {
        is ParsedLayer.Bad -> Result.failure(SettingsWriteException(parsed.error, "settings JSON does not parse"))
        is ParsedLayer.Ok -> replace(parsed.doc) { true }
    }

    /**
     * Replaces, in one edit, every stored key for which [owns] is true with the
     * content of [doc]; other keys stay. Profiles use it to keep app-level keys
     * here while a named profile holds the rest.
     */
    suspend fun replace(doc: LayerDoc, owns: (String) -> Boolean): Result<Unit> = runCatching {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { k ->
                val name = k.name.removePrefix(PREFIX)
                if (LayerDoc.isLanguageKey(name)) {
                    val block = decodeObject(prefs[stringPreferencesKey(k.name)])
                    val kept = block?.filterKeys { !owns(it) }.orEmpty()
                    if (kept.isEmpty()) prefs.remove(k) else prefs[stringPreferencesKey(k.name)] = JsonObject(kept).toString()
                } else if (owns(name)) {
                    prefs.remove(k)
                }
            }
            doc.plain.filterKeys(owns).forEach { (k, v) -> apply(prefs, SettingEdit(k, null, v)) }
            doc.lang.forEach { (l, block) -> block.filterKeys(owns).forEach { (k, v) -> apply(prefs, SettingEdit(k, l, v)) } }
        }
    }

    private fun apply(prefs: MutablePreferences, e: SettingEdit) {
        if (e.language == null) {
            val key = stringPreferencesKey(PREFIX + e.key)
            if (e.value == null) prefs.remove(key) else prefs[key] = e.value.toString()
            return
        }
        val key = stringPreferencesKey("$PREFIX[${e.language}]")
        val block = decodeObject(prefs[key]).orEmpty().toMutableMap()
        if (e.value == null) block.remove(e.key) else block[e.key] = e.value
        if (block.isEmpty()) prefs.remove(key) else prefs[key] = JsonObject(block).toString()
    }

    private fun decode(prefs: Preferences): LayerDoc {
        val plain = HashMap<String, JsonElement>()
        val lang = HashMap<String, Map<String, JsonElement>>()
        val errors = ArrayList<SettingsDiagnostic>()
        for ((k, v) in prefs.asMap()) {
            if (!k.name.startsWith(PREFIX) || v !is String) continue
            val name = k.name.removePrefix(PREFIX)
            val json = (Jsonc.parse(v) as? JsoncResult.Ok)?.root?.value
            if (json == null) { errors += SettingsDiagnostic(DiagnosticCode.INVALID_VALUE, key = name); continue }
            if (!LayerDoc.isLanguageKey(name)) { plain[name] = json; continue }
            val block = json as? JsonObject ?: continue
            LayerDoc.languagesOf(name).forEach { lang[it] = block }
        }
        return LayerDoc(LayerDoc.nextVersion(), plain, lang, errors)
    }

    private fun decodeObject(raw: String?): Map<String, JsonElement>? =
        raw?.let { (Jsonc.parse(it) as? JsoncResult.Ok)?.root?.value as? JsonObject }

    companion object {
        const val PREFIX = "setting:"
    }
}

/**
 * Moves values stored before the JSON layer existed onto `setting:<key>`
 * (LLD 4.1): the theme under its pre-schema key `theme_mode`, and the three
 * integer settings the Pillar 5 store kept as typed preferences.
 */
object LegacySettingsMigration : DataMigration<Preferences> {

    private val themeMode = stringPreferencesKey("theme_mode")
    private val ints = listOf("editor.fontSize", "editor.lineHeight", "terminal.fontSize")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        themeMode in currentData || ints.any { k -> currentData.asMap().keys.any { it.name == k } }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val out = currentData.toMutablePreferences()
        currentData[themeMode]?.let { out[stringPreferencesKey(DataStoreUserLayer.PREFIX + SettingsSchema.themeMode.key)] = JsonPrimitive(it).toString() }
        out.remove(themeMode)
        for (name in ints) {
            val raw = currentData.asMap().entries.firstOrNull { it.key.name == name } ?: continue
            // Typed as Int by the old store; anything else was never valid and is dropped.
            (raw.value as? Int)?.let { out[stringPreferencesKey(DataStoreUserLayer.PREFIX + name)] = JsonPrimitive(it).toString() }
            out.remove(intPreferencesKey(name))
        }
        return out.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
