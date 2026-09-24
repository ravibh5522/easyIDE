package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns a manifest `configuration` contribution (sdk-reference "Contribution
 * points": `title, order?, properties{key: JSON Schema + default, scope, enum,
 * enumDescriptions, markdownDescription, deprecationMessage}`, one section or
 * an array of them) into [Setting.Contributed] entries. Manifests are
 * untrusted input: every malformed piece becomes a diagnostic, never a crash.
 */
object ContributedSettings {

    data class Parsed(val settings: List<Setting.Contributed>, val diagnostics: List<SettingsDiagnostic>)

    fun parse(owner: String, configuration: JsonElement): Parsed {
        val sections = when (configuration) {
            is JsonObject -> listOf(configuration)
            is JsonArray -> configuration.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        val settings = ArrayList<Setting.Contributed>()
        val diagnostics = ArrayList<SettingsDiagnostic>()
        if (sections.isEmpty()) diagnostics += SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_BAD_DESCRIPTOR, detail = owner)
        for (section in sections) {
            val group = SettingGroup.Contributed(
                owner = owner,
                title = SchemaValidator.stringOrNull(section["title"]) ?: owner,
                order = (section["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            )
            val properties = section["properties"] as? JsonObject ?: continue
            for ((key, descriptor) in properties) {
                if (descriptor !is JsonObject || key.isBlank() || LayerDoc.isLanguageKey(key)) {
                    diagnostics += SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_BAD_DESCRIPTOR, key = key, detail = owner)
                    continue
                }
                settings += property(owner, group, key, descriptor, diagnostics)
            }
        }
        return Parsed(settings, diagnostics)
    }

    /** VS Code `scope` -> sdk-reference scope (LLD sec 5.1). */
    fun scopeOf(manifestScope: String?): SettingScope = when (manifestScope) {
        "application", "machine" -> SettingScope.G
        "machine-overridable", "environment" -> SettingScope.E
        "language-overridable" -> SettingScope.L
        else -> SettingScope.P
    }

    private fun property(
        owner: String, group: SettingGroup.Contributed, key: String, d: JsonObject,
        diagnostics: MutableList<SettingsDiagnostic>,
    ): Setting.Contributed {
        SchemaValidator.stringOrNull(d["pattern"])?.let { p ->
            // Boundary: the pattern is manifest text; an invalid one is reported, then ignored.
            if (runCatching { Regex(p) }.isFailure) {
                diagnostics += SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_BAD_DESCRIPTOR, key = key, detail = owner)
            }
        }
        val declared = d["default"]
        val default = when {
            declared == null -> JsonNull
            SchemaValidator.isValid(d, declared) -> declared
            else -> {
                diagnostics += SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_BAD_DEFAULT, key = key, detail = owner)
                JsonNull
            }
        }
        val description = SchemaValidator.stringOrNull(d["markdownDescription"])?.let(::markdownToPlain)
            ?: SchemaValidator.stringOrNull(d["description"]).orEmpty()
        val deprecation = SchemaValidator.stringOrNull(d["markdownDeprecationMessage"])?.let(::markdownToPlain)
            ?: SchemaValidator.stringOrNull(d["deprecationMessage"])
        return Setting.Contributed(
            key = key,
            owner = owner,
            group = group,
            title = titleOf(key),
            description = description,
            default = default,
            scope = scopeOf(SchemaValidator.stringOrNull(d["scope"])),
            schema = d,
            control = controlOf(d),
            order = (d["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            deprecation = deprecation,
        )
    }

    /** The simplest control that can only produce schema-valid values; anything richer goes to JSON. */
    fun controlOf(d: JsonObject): ContributedControl {
        val type = SchemaValidator.stringOrNull(d["type"])
        val enum = (d["enum"] as? JsonArray)?.map(SchemaValidator::stringOrNull)
        if (!enum.isNullOrEmpty() && enum.all { it != null }) {
            val descriptions = (d["enumDescriptions"] as? JsonArray)?.map { SchemaValidator.stringOrNull(it).orEmpty() }
            return ContributedControl.Choice(enum.filterNotNull(), enum.indices.map { descriptions?.getOrNull(it).orEmpty() })
        }
        val min = SchemaValidator.number(d, "minimum")
        val max = SchemaValidator.number(d, "maximum")
        return when (type) {
            "boolean" -> ContributedControl.Switch
            "integer" -> if (min != null && max != null && min <= max && max - min <= MAX_STEPPER_SPAN) {
                ContributedControl.Stepper(Math.ceil(min).toInt(), Math.floor(max).toInt())
            } else {
                ContributedControl.NumberField
            }
            "number" -> ContributedControl.NumberField
            "string" -> ContributedControl.TextField(SchemaValidator.stringOrNull(d["pattern"]))
            "array" -> {
                val items = d["items"] as? JsonObject
                if (items != null && SchemaValidator.stringOrNull(items["type"]) == "string" && items["enum"] == null) {
                    ContributedControl.StringList
                } else {
                    ContributedControl.JsonOnly
                }
            }
            else -> ContributedControl.JsonOnly
        }
    }

    /** `python.linting.pylintEnabled` -> `Linting: Pylint Enabled`, as VS Code titles contributed keys. */
    fun titleOf(key: String): String {
        val parts = key.split('.').filter { it.isNotEmpty() }
        val shown = if (parts.size > 1) parts.drop(1) else parts
        return shown.joinToString(": ") { part ->
            part.replace(CAMEL_BOUNDARY, " ").replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** Plain text with links kept as "text (url)"; emphasis and code markers dropped. */
    fun markdownToPlain(md: String): String = md
        .replace(MD_LINK) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        .replace(MD_MARKERS, "")
        .trim()

    private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val MD_LINK = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")
    private val MD_MARKERS = Regex("\\*\\*|__|`|(?m)^#+\\s*")

    /** Wider integer ranges get a number field: tapping a stepper thousands of times is not a control. */
    private const val MAX_STEPPER_SPAN = 100.0
}
