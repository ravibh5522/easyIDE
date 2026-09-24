package dev.easyide.app.data.settings

import androidx.annotation.StringRes
import dev.easyide.app.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/** Settings layers, low to high (LLD sec 3.1). */
enum class LayerId { BUILT_IN, EXTENSION, USER, ENVIRONMENT, PROJECT }

enum class Severity { ERROR, WARNING, INFO }

/**
 * Everything the settings system reports about a layer or a file. Codes, not
 * sentences, so the text stays in string resources; [detail] fills the
 * message's single placeholder.
 */
enum class DiagnosticCode(val severity: Severity, @StringRes val message: Int) {
    PARSE_ERROR(Severity.ERROR, R.string.settings_diag_parse),
    NOT_AN_OBJECT(Severity.ERROR, R.string.settings_diag_not_object),
    NOT_AN_ARRAY(Severity.ERROR, R.string.keybinding_diag_not_array),
    FILE_UNREADABLE(Severity.ERROR, R.string.settings_diag_unreadable),
    INVALID_VALUE(Severity.ERROR, R.string.settings_diag_invalid),
    UNKNOWN_KEY(Severity.WARNING, R.string.settings_diag_unknown),
    NOT_IN_THIS_LAYER(Severity.WARNING, R.string.settings_diag_not_in_layer),
    NOT_LANGUAGE_OVERRIDABLE(Severity.WARNING, R.string.settings_diag_not_lang),
    PROTECTED_KEY(Severity.WARNING, R.string.settings_diag_protected),
    DEPRECATED(Severity.WARNING, R.string.settings_diag_deprecated),
    NEEDS_TRUST(Severity.INFO, R.string.settings_diag_needs_trust),
    CONTRIBUTED_BUILT_IN_KEY(Severity.WARNING, R.string.settings_diag_contrib_builtin),
    CONTRIBUTED_DUPLICATE(Severity.WARNING, R.string.settings_diag_contrib_duplicate),
    CONTRIBUTED_BAD_DEFAULT(Severity.WARNING, R.string.settings_diag_contrib_default),
    CONTRIBUTED_BAD_DESCRIPTOR(Severity.WARNING, R.string.settings_diag_contrib_descriptor),
    DEFAULTS_REJECTED(Severity.WARNING, R.string.settings_diag_defaults_rejected),
    BAD_CHORD(Severity.ERROR, R.string.keybinding_diag_chord),
    UNSUPPORTED_WHEN(Severity.ERROR, R.string.keybinding_diag_when),
    UNKNOWN_COMMAND(Severity.WARNING, R.string.keybinding_diag_command),
    BAD_ENTRY(Severity.ERROR, R.string.keybinding_diag_entry),
    CHORD_CONFLICT(Severity.WARNING, R.string.keybinding_diag_conflict),
    BAD_CONTRIBUTION_REF(Severity.WARNING, R.string.settings_diag_bad_ref),
    NOT_HIDEABLE(Severity.WARNING, R.string.settings_diag_not_hideable),
    KEY_ROW_LAYOUT(Severity.WARNING, R.string.settings_diag_key_row_layout),
    LSP_SERVER_INCOMPLETE(Severity.WARNING, R.string.settings_diag_lsp_incomplete),
    LSP_SERVER_FIELD(Severity.WARNING, R.string.settings_diag_lsp_field),
}

/**
 * [offset] points into the source text when there is one; [key] names the
 * setting concerned; [parseError] says why a [DiagnosticCode.PARSE_ERROR] happened.
 */
data class SettingsDiagnostic(
    val code: DiagnosticCode,
    val key: String? = null,
    val detail: String? = null,
    val offset: Int? = null,
    val parseError: JsoncError? = null,
) {
    val severity: Severity get() = code.severity
}

/**
 * One layer's values: plain keys, and per-language blocks from `"[python]"` or
 * VS Code's combined `"[python][markdown]"` keys. [version] changes on every
 * reload so resolution memos keyed on it never serve stale values.
 */
data class LayerDoc(
    val version: Long,
    val plain: Map<String, JsonElement>,
    val lang: Map<String, Map<String, JsonElement>>,
    val errors: List<SettingsDiagnostic> = emptyList(),
) {
    val isEmpty: Boolean get() = plain.isEmpty() && lang.isEmpty()

    fun value(key: String, language: String?): JsonElement? =
        if (language == null) plain[key] else lang[language]?.get(key)

    /** Back to settings.json shape: plain keys sorted, then one block per language. */
    fun toJson(): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        plain.keys.sorted().forEach { out[it] = plain.getValue(it) }
        lang.keys.sorted().forEach { l -> out["[$l]"] = JsonObject(lang.getValue(l).toSortedMap()) }
        return JsonObject(out)
    }

    fun withEdits(edits: List<SettingEdit>): LayerDoc {
        val p = plain.toMutableMap()
        val l = lang.mapValues { it.value.toMutableMap() }.toMutableMap()
        for (e in edits) {
            val target = if (e.language == null) p else l.getOrPut(e.language) { mutableMapOf() }
            if (e.value == null) target.remove(e.key) else target[e.key] = e.value
        }
        return LayerDoc(nextVersion(), p, l.filterValues { it.isNotEmpty() }, errors)
    }

    fun withErrors(errors: List<SettingsDiagnostic>): LayerDoc = copy(version = nextVersion(), errors = errors)

    companion object {
        private val counter = AtomicLong()

        fun nextVersion(): Long = counter.incrementAndGet()

        val EMPTY = LayerDoc(0, emptyMap(), emptyMap())

        private val LANG_KEY = Regex("^(\\[[^\\[\\]]+])+$")
        private val LANG_ID = Regex("\\[([^\\[\\]]+)]")

        fun isLanguageKey(key: String): Boolean = LANG_KEY.matches(key)

        /** Language ids of a `"[a][b]"` key. */
        fun languagesOf(key: String): List<String> = LANG_ID.findAll(key).map { it.groupValues[1] }.toList()

        /**
         * Splits a settings object into plain and language blocks. A language key
         * whose value is not an object is dropped (the JSON editor flags it).
         */
        fun fromJson(obj: JsonObject, errors: List<SettingsDiagnostic> = emptyList()): LayerDoc {
            val plain = LinkedHashMap<String, JsonElement>()
            val lang = LinkedHashMap<String, MutableMap<String, JsonElement>>()
            for ((k, v) in obj) {
                if (!isLanguageKey(k)) { plain[k] = v; continue }
                val block = v as? JsonObject ?: continue
                languagesOf(k).forEach { id -> lang.getOrPut(id) { LinkedHashMap() }.putAll(block) }
            }
            return LayerDoc(nextVersion(), plain, lang, errors)
        }

        /** Parses a whole settings text; on failure returns the error for "keep last good" handling. */
        fun parse(text: String): ParsedLayer {
            if (text.isBlank()) return ParsedLayer.Ok(LayerDoc(nextVersion(), emptyMap(), emptyMap()))
            return when (val r = Jsonc.parse(text)) {
                is JsoncResult.Failure -> ParsedLayer.Bad(
                    SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, offset = r.offset, parseError = r.error),
                )
                is JsoncResult.Ok -> (r.root.value as? JsonObject)?.let { ParsedLayer.Ok(fromJson(it)) }
                    ?: ParsedLayer.Bad(SettingsDiagnostic(DiagnosticCode.NOT_AN_OBJECT, offset = r.root.start))
            }
        }
    }
}

sealed interface ParsedLayer {
    data class Ok(val doc: LayerDoc) : ParsedLayer
    data class Bad(val error: SettingsDiagnostic) : ParsedLayer
}
