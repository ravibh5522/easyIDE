package dev.easyide.extensions.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/** Which environment and project the runtime is serving (extension-runtime.md sec 4). */
data class RuntimeScope(val envId: String?, val projectId: String?) {
    companion object { val NONE = RuntimeScope(null, null) }
}

/** The resolution coordinates of one settings read (customization.md sec 3.2). */
data class SettingsQuery(val languageId: String?, val envId: String?, val projectId: String?) {
    companion object {
        fun of(scope: RuntimeScope, languageId: String? = null) = SettingsQuery(languageId, scope.envId, scope.projectId)
    }
}

/** Where `setConfig`/`toggleConfig` write (sdk-reference `target`). */
enum class ConfigTarget(val wire: String) {
    USER("user"), LANGUAGE("language"), ENVIRONMENT("environment"), PROJECT("project");

    companion object {
        fun parse(wire: String): ConfigTarget? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Settings as the runtime sees them. The store, the layering and the schema live in `:app`
 * (customization.md); this module only reads resolved values and asks for writes, so it
 * stays free of DataStore and Android types.
 */
interface SettingsPort {
    /**
     * The resolved value of [key] for [query] after layering, or null when no layer sets it
     * (callers then use the sdk-reference default, see [ExtensionSettings]).
     */
    fun value(key: String, query: SettingsQuery): JsonElement?

    /** Bumps on every change to any layer; observers re-read what they depend on. */
    val version: StateFlow<Long>

    /**
     * The active profile's `enabledExtensions` allowlist (sdk-reference `profiles/<name>.json`),
     * or null when the profile does not restrict extensions.
     */
    fun profileExtensions(): Set<String>?

    /**
     * Writes [value] (null removes the key) at [target]; [query] supplies the language for
     * `language` targets and the environment/project for the others. Called only after the
     * runtime's own checks (protected keys, ownership, `ui.settings`).
     */
    suspend fun write(key: String, value: JsonElement?, target: ConfigTarget, query: SettingsQuery)
}
