package dev.easyide.app.lsp

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.lsp.LspSettings
import dev.easyide.lsp.session.ConfigurationItem
import dev.easyide.lsp.session.ConfigurationProvider
import dev.easyide.lsp.session.ServerKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The resolved global `lsp.*` settings as `:lsp` wants them (it holds no defaults itself). */
fun SettingsSnapshot.lspSettings(): LspSettings = LspSettings(
    requestTimeoutMs = this[LspSettingsSchema.requestTimeoutMs].toLong(),
    didChangeDebounceMs = this[LspSettingsSchema.didChangeDebounceMs].toLong(),
    restartMaxRetries = this[LspSettingsSchema.restartMaxRetries],
    restartBackoffMs = this[LspSettingsSchema.restartBackoffMs].toLong(),
    globalMemoryBudgetMb = this[LspSettingsSchema.globalMemoryBudgetMb],
    maxServers = this[LspSettingsSchema.maxServers],
    trace = this[LspSettingsSchema.trace],
)

/**
 * `workspace/configuration` and `didChangeConfiguration` answered from settings resolution
 * (LSP-10): a section is the tree of every declared setting under that dotted prefix, so
 * `python` answers `{"analysis": {...}}` for `python.analysis.*` keys, as VS Code does.
 * Values are the resolved ones - defaults included - in their JSON wire form.
 */
class SettingsConfigurationProvider(
    /** The settings of the server's (environment, project): project values win (PLT-04). */
    private val snapshots: (ServerKey) -> StateFlow<SettingsSnapshot>,
    private val settings: () -> List<Setting<*>>,
) : ConfigurationProvider {

    override suspend fun configuration(key: ServerKey, items: List<ConfigurationItem>): List<JsonElement> {
        val current = snapshots(key).value
        return items.map { sectionOf(current, it.section) }
    }

    override fun section(key: ServerKey, section: String): Flow<JsonElement> =
        snapshots(key).map { sectionOf(it, section) }.distinctUntilChanged()

    /** The subtree for [section] (the whole tree for null), or JSON null when nothing is under it. */
    internal fun sectionOf(snapshot: SettingsSnapshot, section: String?): JsonElement {
        val all = settings()
        all.firstOrNull { it.key == section }?.let { return valueJson(it, snapshot) }
        val entries = all.mapNotNull { s ->
            val rest = when {
                section == null -> s.key
                s.key.startsWith("$section.") -> s.key.substring(section.length + 1)
                else -> return@mapNotNull null
            }
            rest.split('.') to valueJson(s, snapshot)
        }
        return if (entries.isEmpty()) JsonNull else nest(entries)
    }

    private fun nest(entries: List<Pair<List<String>, JsonElement>>): JsonObject {
        val groups = LinkedHashMap<String, MutableList<Pair<List<String>, JsonElement>>>()
        val leaves = LinkedHashMap<String, JsonElement>()
        for ((path, value) in entries) {
            if (path.size == 1) leaves[path[0]] = value else groups.getOrPut(path[0]) { ArrayList() } += path.drop(1) to value
        }
        // A key that is both a value and a prefix (rare) keeps the object: servers read nested keys.
        return JsonObject(leaves + groups.mapValues { (_, v) -> nest(v) })
    }

    companion object {
        /** A resolved value in its settings-file JSON form (the schema's own encoding). */
        fun valueJson(setting: Setting<*>, snapshot: SettingsSnapshot): JsonElement = encoded(setting, snapshot)

        private fun <T> encoded(setting: Setting<T>, snapshot: SettingsSnapshot): JsonElement = setting.encode(snapshot[setting])
    }
}
