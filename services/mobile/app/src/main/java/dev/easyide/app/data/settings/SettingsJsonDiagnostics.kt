package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * In-app validation of a settings text for the "Edit as JSON" screen (LLD sec
 * 16). Nothing here blocks a save except a parse error: schema-invalid values
 * are saved as written (user intent) and skipped by resolution, and the list
 * tells the user which ones will be.
 */
object SettingsJsonDiagnostics {

    fun check(text: String, layer: LayerId, schema: SchemaState): List<SettingsDiagnostic> {
        if (text.isBlank()) return emptyList()
        val root = when (val r = Jsonc.parse(text)) {
            is JsoncResult.Failure -> return listOf(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, offset = r.offset, parseError = r.error))
            is JsoncResult.Ok -> r.root
        }
        if (root.value !is JsonObject) return listOf(SettingsDiagnostic(DiagnosticCode.NOT_AN_OBJECT, offset = root.start))
        val out = ArrayList<SettingsDiagnostic>()
        for (m in root.members) {
            if (!LayerDoc.isLanguageKey(m.key)) { checkKey(m.key, m.node.value, false, m.keyStart, layer, schema, out); continue }
            if (m.node.value !is JsonObject) { out += SettingsDiagnostic(DiagnosticCode.INVALID_VALUE, m.key, offset = m.keyStart); continue }
            m.node.members.forEach { inner -> checkKey(inner.key, inner.node.value, true, inner.keyStart, layer, schema, out) }
        }
        return out
    }

    /** True when saving must be refused: the text would not parse back. */
    fun blocksSave(diagnostics: List<SettingsDiagnostic>): Boolean =
        diagnostics.any { it.code in BLOCKING }

    private fun checkKey(
        key: String, value: JsonElement, inLanguageBlock: Boolean, offset: Int,
        layer: LayerId, schema: SchemaState, out: MutableList<SettingsDiagnostic>,
    ) {
        fun add(code: DiagnosticCode, detail: String? = null) { out += SettingsDiagnostic(code, key, detail, offset) }
        if (!SettingsPolicy.layerMayHold(layer, key)) add(DiagnosticCode.PROTECTED_KEY)
        val setting = schema.byKey[key]
        if (setting == null) {
            add(DiagnosticCode.UNKNOWN_KEY)
        } else {
            if (!setting.scope.allows(layer)) add(DiagnosticCode.NOT_IN_THIS_LAYER)
            if (inLanguageBlock && !setting.scope.languageOverridable) add(DiagnosticCode.NOT_LANGUAGE_OVERRIDABLE)
            if (!setting.isValid(value)) add(DiagnosticCode.INVALID_VALUE, invalidDetail(setting, value))
            setting.deprecation?.let { add(DiagnosticCode.DEPRECATED, it) }
        }
        if (layer == LayerId.PROJECT && ExecBearing.extract(LayerDoc.fromJson(JsonObject(mapOf(key to value))), schema).isNotEmpty()) {
            add(DiagnosticCode.NEEDS_TRUST)
        }
    }

    private val BLOCKING = setOf(DiagnosticCode.PARSE_ERROR, DiagnosticCode.NOT_AN_OBJECT, DiagnosticCode.NOT_AN_ARRAY)

    private fun invalidDetail(setting: Setting<*>, value: JsonElement): String? =
        (setting as? Setting.Contributed)?.let { SchemaValidator.validate(it.schema, value).firstOrNull() }
}
