package dev.easyide.app.extensions

import dev.easyide.app.data.settings.ConfigurationContribution
import dev.easyide.extensions.manifest.ExtensionDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `contributes.configuration` / `configurationDefaults` (EXT-25) as the
 * [dev.easyide.app.data.settings.SettingsRegistry] takes them: the manifest shape, rebuilt
 * from the validated descriptor (sections with their properties, `[lang]` default blocks),
 * so the registry applies its own schema parsing and conflict rules.
 */
object ConfigurationContributions {

    fun of(d: ExtensionDescriptor): ConfigurationContribution {
        val c = d.contributes
        val sections = c.configuration.groupBy { it.section to it.sectionOrder }.map { (section, props) ->
            JsonObject(buildMap {
                section.first?.let { put(TITLE, JsonPrimitive(it)) }
                section.second?.let { put(ORDER, JsonPrimitive(it)) }
                put(PROPERTIES, JsonObject(props.associate { it.key to it.schema }))
            })
        }
        val plain = c.configurationDefaults.filter { it.language == null }.associate { it.key to it.value }
        val byLang: Map<String, JsonElement> = c.configurationDefaults.filter { it.language != null }
            .groupBy { it.language!! }
            .map { (lang, entries) -> "[$lang]" to JsonObject(entries.associate { it.key to it.value }) }
            .toMap()
        return ConfigurationContribution(
            owner = d.id.value,
            configuration = if (sections.isEmpty()) null else JsonArray(sections),
            configurationDefaults = if (plain.isEmpty() && byLang.isEmpty()) null else JsonObject(plain + byLang),
        )
    }

    private const val TITLE = "title"
    private const val ORDER = "order"
    private const val PROPERTIES = "properties"
}
