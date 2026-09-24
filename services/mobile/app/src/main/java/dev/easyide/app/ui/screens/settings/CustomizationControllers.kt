package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.SettingEdit
import dev.easyide.app.data.settings.SettingsResolver
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.extensions.adapters.ContributedKeybindings
import dev.easyide.app.lsp.servers.LanguageServerRows
import dev.easyide.app.lsp.servers.ServerRegistry
import dev.easyide.app.lsp.servers.ServersView
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.EffectiveBinding
import dev.easyide.app.ui.commands.KeybindingList
import dev.easyide.app.ui.commands.KeybindingsFile
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.extensions.contrib.ContributionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The Keyboard Shortcuts screen: effective bindings with their layer, conflicts, and the command ids to bind. */
data class KeybindingsScreenState(val list: KeybindingList.Result, val commands: List<String>)

/**
 * Keyboard Shortcuts (customization.md sec 7.3): the effective keymap (built-in table,
 * contributed keybindings, the active profile's keybindings.json) and add/remove, which
 * write keybindings.json through the JSON editor's controller.
 */
class KeybindingsController(
    scope: CoroutineScope,
    profiles: ProfileManager,
    contributions: ContributionRegistry,
    private val files: SettingsJsonEditorController,
    private val onFailed: () -> Unit,
) {
    val state: StateFlow<KeybindingsScreenState?> = combine(
        profiles.keybindings.text,
        contributions.keybindings.entries,
        contributions.commands.entries,
    ) { text, entries, commands ->
        val known = CommandIds.ALL + commands.map { it.value.command }
        val extension = ContributedKeybindings.owned(entries) { _, _ -> }
        KeybindingsScreenState(KeybindingList.build(Keymap.DEFAULT.bindings, extension, KeybindingsFile.parse(text), known), known.sorted())
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    fun add(key: String, command: String, whenText: String?) =
        files.editKeybindings({ KeybindingList.add(it, key.trim(), command.trim(), whenText) }) { ok -> if (!ok) onFailed() }

    fun remove(binding: EffectiveBinding) =
        files.editKeybindings({ KeybindingList.remove(it, binding) }) { ok -> if (!ok) onFailed() }

    private companion object { const val SUBSCRIPTION_TIMEOUT_MS = 5_000L }
}

/** What the Language servers screen shows for the selected layer tab. */
data class LanguageServersState(
    val view: ServersView,
    val layer: LayerId,
    /** Whose packs' servers are listed; null when there is no environment yet (custom servers only). */
    val environmentId: String?,
    /** The edited layer's own `lsp.servers` (raw: never trust-stripped, so writes keep every field). */
    val layerValue: JsonObject?,
    /** Keys a lower layer switched off, so switching on here must write `enabled: true`. */
    val offBelow: Set<String>,
)

/**
 * Language servers (customization.md sec 12): resolved servers for the tab's (environment,
 * project) and add/edit/remove of custom servers and on/off of any server, written into
 * the tab's layer of `lsp.servers`. On the User tab the servers of [fallbackEnvironment]
 * (the default or first environment) are listed, since packs are per environment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageServersController(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val servers: ServerRegistry,
    private val tab: StateFlow<LayerTab>,
    fallbackEnvironment: Flow<String?>,
    private val onFailed: () -> Unit,
) {
    val state: StateFlow<LanguageServersState?> = combine(tab, fallbackEnvironment) { t, fallback -> t to fallback }
        .flatMapLatest { (t, fallback) ->
            val env = when (t) {
                is LayerTab.Project -> t.envId
                is LayerTab.Environment -> t.envId
                LayerTab.User -> fallback
            }
            val source = store.sourceFor(t.target)
            combine(
                env?.let(servers::declarations) ?: flowOf(emptyList()),
                store.snapshot(t.query),
                source?.doc ?: flowOf(null),
            ) { declared, snapshot, doc ->
                val layerValue = doc?.plain?.get(LspSettingsSchema.servers.key) as? JsonObject
                LanguageServersState(LanguageServerRows.build(declared, snapshot, layerValue), t.layer, env, layerValue, offBelow(snapshot, t.layer))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    fun setEnabled(key: String, enabled: Boolean) = edit { s -> LanguageServerRows.withEnabled(s.layerValue, key, enabled, key in s.offBelow) }

    /**
     * Adds or edits the custom server [key] in this layer ([previousKey] = the key it had,
     * for a rename). False when the form is incomplete; nothing is written then.
     */
    fun saveCustom(key: String, languages: String, command: String, previousKey: String?): Boolean {
        val current = state.value ?: return false
        if (!LanguageServerRows.isValidCustomKey(key)) return false
        val previous = (current.layerValue?.get(previousKey ?: key) as? JsonObject)
        val entry = LanguageServerRows.customEntry(languages, command, previous) ?: return false
        edit { s ->
            val base = if (previousKey != null && previousKey != key) LanguageServerRows.withEntry(s.layerValue, previousKey, null) else s.layerValue
            LanguageServerRows.withEntry(base, key, entry)
        }
        return true
    }

    /** Removes [key]'s entry from this layer: a custom server defined here disappears, a pack server's overrides reset. */
    fun remove(key: String) = edit { s -> LanguageServerRows.withEntry(s.layerValue, key, null) }

    private fun edit(next: (LanguageServersState) -> JsonObject?) {
        val s = state.value ?: return
        val target = tab.value.target
        scope.launch {
            // Re-read the raw layer at write time, so a concurrent file edit is not undone.
            val fresh = store.sourceFor(target)?.doc?.first()?.plain?.get(LspSettingsSchema.servers.key) as? JsonObject
            val value = next(s.copy(layerValue = fresh))
            store.write(target, listOf(SettingEdit(LspSettingsSchema.servers.key, null, value))).onFailure { onFailed() }
        }
    }

    private fun offBelow(snapshot: SettingsSnapshot, layer: LayerId): Set<String> {
        val below = snapshot.layers.filter { it.id.ordinal < layer.ordinal }
            .mapNotNull { it.doc.plain[LspSettingsSchema.servers.key] as? JsonObject }
            .fold(JsonObject(emptyMap())) { acc, v -> SettingsResolver.mergeJson(acc, v, 2) as JsonObject }
        return below.filterValues { (it as? JsonObject)?.get("enabled").let { e -> (e as? JsonPrimitive)?.booleanOrNull == false } }.keys
    }

    private companion object { const val SUBSCRIPTION_TIMEOUT_MS = 5_000L }
}
