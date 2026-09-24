package dev.easyide.app.extensions

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.app.data.settings.SettingEdit
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.data.settings.SettingsWriter
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import dev.easyide.app.data.settings.ConfigTarget as AppTarget
import dev.easyide.app.data.settings.SettingsQuery as AppQuery

/**
 * The extension runtime's view of settings ([SettingsPort]) over the app's layered
 * [SettingsStore] (customization.md): built-in default < extension `configurationDefaults`
 * < user < environment < project, `[lang]` blocks beating plain values inside a layer.
 *
 * Reads are synchronous (when-clauses evaluate on Main), so the resolved snapshots of
 * the runtime's current scope and of the global scope are mirrored into memory as they
 * change; a query for any other scope reads the global snapshot. Writes go through the
 * store's validation as an extension writer: the action runner has already checked
 * ownership and `ui.settings` (it knows the owner), and the store still refuses
 * protected keys, scope violations and invalid values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsPort(
    private val store: SettingsStore,
    profiles: ProfileManager,
    private val log: ExtensionLog,
    scope: CoroutineScope,
) : SettingsPort {

    private val runtimeScope = MutableStateFlow(RuntimeScope.NONE)
    @Volatile private var global: SettingsSnapshot? = null
    @Volatile private var scoped: Pair<RuntimeScope, SettingsSnapshot>? = null
    @Volatile private var allowlist: Set<String>? = null
    private val versionState = MutableStateFlow(0L)
    override val version: StateFlow<Long> = versionState.asStateFlow()

    init {
        scope.launch { store.snapshot.collect { global = it; bump() } }
        scope.launch {
            runtimeScope
                .flatMapLatest { rs -> store.snapshot(AppQuery(rs.envId, rs.projectId)).map { rs to it } }
                .collect { scoped = it; bump() }
        }
        scope.launch {
            profiles.active.collect { name ->
                allowlist = if (name == SettingsPolicy.DEFAULT_PROFILE) null
                else profiles.profile(name).load().getOrNull()?.enabledExtensions?.mapNotNullTo(HashSet()) { it.stringOrNull }
                bump()
            }
        }
    }

    /** The environment/project the runtime serves; set alongside `ExtensionsRuntime.setRuntimeScope`. */
    fun setScope(scope: RuntimeScope) { runtimeScope.value = scope }

    override fun value(key: String, query: SettingsQuery): JsonElement? {
        val s = scoped?.takeIf { (rs, _) -> rs.envId == query.envId && rs.projectId == query.projectId }?.second ?: global ?: return null
        return s.raw(key, query.languageId)?.value
    }

    /** The active profile's `enabledExtensions`; null for the default profile or one without a list. */
    override fun profileExtensions(): Set<String>? = allowlist

    override suspend fun write(key: String, value: JsonElement?, target: ConfigTarget, query: SettingsQuery) {
        val appTarget = when (target) {
            ConfigTarget.USER, ConfigTarget.LANGUAGE -> AppTarget.USER
            ConfigTarget.ENVIRONMENT -> AppTarget(LayerId.ENVIRONMENT, envId = query.envId)
            ConfigTarget.PROJECT -> AppTarget(LayerId.PROJECT, envId = query.envId, projectId = query.projectId)
        }
        val language = query.languageId.takeIf { target == ConfigTarget.LANGUAGE }
        val writer = SettingsWriter.Extension(id = EXTENSION_WRITER, mayWriteForeign = true)
        store.write(appTarget, listOf(SettingEdit(key, language, value)), writer).onFailure { e ->
            log.append(LogEntry(null, LogLevel.WARN, "settings: '$key' not written to the ${target.wire} layer: ${e.message}"))
        }
    }

    private fun bump() = versionState.update { it + 1 }

    private companion object {
        /** The runner has resolved ownership; the store only needs to know an extension writes. */
        const val EXTENSION_WRITER = "extension"
    }
}
