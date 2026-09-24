package dev.easyide.app.lsp.servers

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot

/**
 * What decides whether a resolved server may start, beyond its own `enabled`:
 * `lsp.enabled` per language (customization.md sec 13: `"[python]": {"lsp.enabled": false}`
 * starts no server for Python) and safe mode (sec 11: `lsp.servers` entries defined without
 * an extension are not started).
 *
 * @property lspEnabled the plain `lsp.enabled` value.
 * @property byLanguage `lsp.enabled` resolved for every language that has a `[lang]` block in
 *   any layer; languages absent here take [lspEnabled].
 */
data class ServerGates(
    val lspEnabled: Boolean,
    val byLanguage: Map<String, Boolean> = emptyMap(),
    val safeMode: Boolean = false,
) {
    fun enabledFor(languageId: String): Boolean = byLanguage[languageId] ?: lspEnabled

    /**
     * Applies the gates to a merge result: a server keeps only its enabled languages and is
     * disabled when none remain; in safe mode a settings-only server (no declaring
     * extension) is disabled. Disabled rather than removed, so the status UI still lists it.
     */
    fun apply(result: MergeResult): MergeResult = result.copy(
        servers = result.servers.map { s ->
            val languages = s.config.languages.filterTo(LinkedHashSet(), ::enabledFor)
            val blockedBySafeMode = safeMode && s.extensionId == null
            val enabled = s.config.enabled && languages.isNotEmpty() && !blockedBySafeMode
            if (languages == s.config.languages && enabled == s.config.enabled) s
            else s.copy(config = s.config.copy(languages = languages.ifEmpty { s.config.languages }, enabled = enabled))
        },
    )

    companion object {
        /** Reads the gates from [s]; every `[lang]` block of every layer is a candidate language. */
        fun from(s: SettingsSnapshot, safeMode: Boolean): ServerGates {
            val languages = s.layers.flatMapTo(sortedSetOf()) { it.doc.lang.keys }
            return ServerGates(
                lspEnabled = s[LspSettingsSchema.enabled],
                byLanguage = languages.associateWith { s.get(LspSettingsSchema.enabled, it) },
                safeMode = safeMode,
            )
        }
    }
}
