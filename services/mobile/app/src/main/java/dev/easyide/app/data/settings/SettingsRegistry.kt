package dev.easyide.app.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One enabled extension's settings contributions, as its manifest declares them.
 * The extension runtime hands a list of these, in `EnabledSet` order, to
 * [SettingsRegistry.setContributions] whenever the enabled set changes.
 */
data class ConfigurationContribution(
    val owner: String,
    val configuration: JsonElement?,
    val configurationDefaults: JsonObject?,
)

/** A problem with one extension's contribution, for the Extension Log and `easyide validate`. */
data class ContributionDiagnostic(val owner: String, val diagnostic: SettingsDiagnostic)

/**
 * The schema at one moment: built-ins plus accepted contributions, and the
 * EXTENSION layer built from `configurationDefaults` - one [Layer] per
 * extension, in `EnabledSet` order, so resolution's high-to-low walk gives a
 * later extension precedence and provenance names the extension that won.
 */
data class SchemaState(
    val version: Long,
    val settings: List<Setting<*>>,
    val byKey: Map<String, Setting<*>>,
    val extensionDefaults: List<Layer>,
    val diagnostics: List<ContributionDiagnostic>,
) {
    companion object {
        fun builtInOnly(builtIns: List<Setting<*>>) =
            SchemaState(0, builtIns, builtIns.associateBy { it.key }, emptyList(), emptyList())
    }
}

/**
 * The dynamic settings schema (LLD sec 5). Built-ins are fixed at construction;
 * contributions are replaced wholesale on every enabled-set change, which makes
 * the conflict rules order-deterministic instead of depending on install history.
 */
class SettingsRegistry(private val builtIns: List<Setting<*>>) {

    private val _state = MutableStateFlow(SchemaState.builtInOnly(builtIns))
    val state: StateFlow<SchemaState> = _state.asStateFlow()

    init {
        require(builtIns.map { it.key }.toSet().size == builtIns.size) { "duplicate built-in setting key" }
    }

    /**
     * Conflict rules (LLD 5.2): a contributed key equal to a built-in key is
     * ignored (use `configurationDefaults` to change a built-in's default); the
     * same key from two extensions belongs to the earlier one in [contributions].
     * An owner missing from the list loses its entries; stored values stay.
     */
    fun setContributions(contributions: List<ConfigurationContribution>) {
        val byKey = LinkedHashMap<String, Setting<*>>()
        builtIns.forEach { byKey[it.key] = it }
        val diagnostics = ArrayList<ContributionDiagnostic>()
        for (c in contributions) {
            val config = c.configuration ?: continue
            val parsed = ContributedSettings.parse(c.owner, config)
            parsed.diagnostics.forEach { diagnostics += ContributionDiagnostic(c.owner, it) }
            for (s in parsed.settings) {
                val existing = byKey[s.key]
                when {
                    existing == null -> byKey[s.key] = s
                    existing is Setting.Contributed -> diagnostics += ContributionDiagnostic(
                        c.owner, SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_DUPLICATE, key = s.key, detail = existing.owner),
                    )
                    else -> diagnostics += ContributionDiagnostic(
                        c.owner, SettingsDiagnostic(DiagnosticCode.CONTRIBUTED_BUILT_IN_KEY, key = s.key),
                    )
                }
            }
        }
        val defaults = contributions.mapNotNull { c ->
            val raw = c.configurationDefaults ?: return@mapNotNull null
            Layer(LayerId.EXTENSION, defaultsDoc(c.owner, raw, byKey, diagnostics), "extension ${c.owner}")
        }
        _state.value = SchemaState(LayerDoc.nextVersion(), byKey.values.toList(), byKey, defaults, diagnostics)
    }

    /**
     * Drops default values that fail their target's schema or target a protected
     * key (M-11: an extension must not change what gets executed by shipping a default).
     * Undeclared targets are kept: they may belong to an extension enabled later.
     */
    private fun defaultsDoc(
        owner: String, raw: JsonObject, schema: Map<String, Setting<*>>,
        diagnostics: MutableList<ContributionDiagnostic>,
    ): LayerDoc {
        val doc = LayerDoc.fromJson(raw)
        fun keep(key: String, value: JsonElement): Boolean {
            val target = schema[key]
            val ok = !SettingsPolicy.isExtensionUnwritable(key) && (target == null || target.isValid(value))
            if (!ok) diagnostics += ContributionDiagnostic(owner, SettingsDiagnostic(DiagnosticCode.DEFAULTS_REJECTED, key = key))
            return ok
        }
        return LayerDoc(
            version = doc.version,
            plain = doc.plain.filter { (k, v) -> keep(k, v) },
            lang = doc.lang.mapValues { (_, block) ->
                block.filter { (k, v) -> keep(k, v) && schema[k]?.scope?.languageOverridable != false }
            }.filterValues { it.isNotEmpty() },
        )
    }
}
