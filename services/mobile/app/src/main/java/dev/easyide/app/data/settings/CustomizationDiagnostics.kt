package dev.easyide.app.data.settings

import dev.easyide.app.extensions.adapters.UserKeyRows
import dev.easyide.app.lsp.servers.ServerConfigMerge
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.settings.ExtensionSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON-editor checks inside the values of the structured customization keys (LLD sec 16),
 * beyond the schema's shape check: bad or non-hideable contribution refs, `keyRows.layouts`
 * rows that will be skipped, and `lsp.servers` entries that cannot define a server or have
 * ignored fields. Warnings only: the runtime skips exactly what is reported.
 */
object CustomizationDiagnostics {

    fun check(key: String, value: JsonElement): List<SettingsDiagnostic> = when (key) {
        ExtensionSettings.WORKBENCH_HIDDEN -> hidden(key, value)
        WorkbenchSettingsSchema.keyRowLayouts.key ->
            UserKeyRows.decode(value).problems.map { p -> SettingsDiagnostic(DiagnosticCode.KEY_ROW_LAYOUT, key, "row ${p.index + 1}: ${p.message}") }
        LspSettingsSchema.servers.key -> servers(value)
        else -> emptyList()
    }

    private fun hidden(key: String, value: JsonElement): List<SettingsDiagnostic> =
        (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.mapNotNull { ref ->
            when {
                ContributionRef.parse(ref) == null -> SettingsDiagnostic(DiagnosticCode.BAD_CONTRIBUTION_REF, key, ref)
                !ContributionOverrides.isHideable(ref) -> SettingsDiagnostic(DiagnosticCode.NOT_HIDEABLE, key, ref)
                else -> null
            }
        }

    /**
     * A key in `<extId>/<id>` form may override a pack's server field by field, so only
     * free-named keys must carry `languages` and `command` (whether a pack declares a key
     * depends on the environment, which a settings file does not know).
     */
    private fun servers(value: JsonElement): List<SettingsDiagnostic> {
        val root = value as? JsonObject ?: return emptyList()
        return root.flatMap { (server, entry) ->
            val fields = ServerConfigMerge.fieldProblems(entry).map { SettingsDiagnostic(DiagnosticCode.LSP_SERVER_FIELD, server, it) }
            val o = entry as? JsonObject
            val missing = if (o == null || server.contains('/')) emptyList() else listOfNotNull(
                LANGUAGES.takeIf { (o[LANGUAGES] as? JsonArray).isNullOrEmpty() },
                COMMAND.takeIf { (o[COMMAND] as? JsonArray).isNullOrEmpty() },
            )
            fields + listOfNotNull(missing.takeIf { it.isNotEmpty() }?.let { SettingsDiagnostic(DiagnosticCode.LSP_SERVER_INCOMPLETE, server, it.joinToString(" and ")) })
        }
    }

    private const val LANGUAGES = "languages"
    private const val COMMAND = "command"
}
