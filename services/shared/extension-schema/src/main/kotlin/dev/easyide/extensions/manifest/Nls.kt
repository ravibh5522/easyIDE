package dev.easyide.extensions.manifest

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Locale

/**
 * `%key%` substitution (EXT-09). A manifest string that is exactly `%key%` is replaced from
 * the first bundle that has the key, most specific locale first, then the default bundle.
 * Only whole strings are replaced (VS Code semantics), so a literal `%` elsewhere is safe.
 */
internal class Nls(private val bundles: List<Pair<String, Map<String, String>>>) {

    fun apply(value: JsonElement, warnings: MutableList<Diagnostic>, pointer: String = ""): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> apply(v, warnings, JsonPointer.child(pointer, k)) })
        is JsonArray -> JsonArray(value.mapIndexed { i, v -> apply(v, warnings, JsonPointer.index(pointer, i)) })
        is JsonPrimitive -> substitute(value, warnings, pointer)
    }

    private fun substitute(p: JsonPrimitive, warnings: MutableList<Diagnostic>, pointer: String): JsonPrimitive {
        val s = p.stringOrNull ?: return p
        val m = PLACEHOLDER.matchEntire(s) ?: return p
        val key = m.groupValues[1]
        val hit = bundles.firstNotNullOfOrNull { it.second[key] }
        if (hit == null) {
            warnings += Diagnostic.warning(DiagnosticCode.NLS_MISSING, pointer, "no localized string for %$key%; the literal is kept")
            return p
        }
        return JsonPrimitive(hit)
    }

    companion object {
        private val PLACEHOLDER = Regex("%([^%\\s]+)%")
        private const val DIR = "l10n/"
        private const val BASE = "package.nls"

        /**
         * Bundle search order for [locale] `pt-BR`: `l10n/package.nls.pt-br.json`,
         * `l10n/package.nls.pt.json`, `l10n/package.nls.json`, `package.nls.json` (the last is
         * where VS Code packages keep their default bundle).
         */
        fun candidates(locale: Locale): List<String> {
            val tag = locale.toLanguageTag().lowercase()
            val lang = locale.language.lowercase()
            val specific = listOf(tag, lang).filter { it.isNotEmpty() && it != "und" }.distinct()
            return specific.map { "$DIR$BASE.$it.json" } + "$DIR$BASE.json" + "$BASE.json"
        }

        /** I/O boundary: an unreadable or malformed bundle is skipped with a warning. */
        fun load(files: PackageFiles, locale: Locale, warnings: MutableList<Diagnostic>): Nls {
            val bundles = candidates(locale).filter(files::exists).mapNotNull { path ->
                val text = try { files.read(path).decodeToString() } catch (e: IOException) {
                    warnings += Diagnostic.warning(DiagnosticCode.NLS_INVALID, "", "cannot read: ${e.message}", path)
                    return@mapNotNull null
                }
                when (val parsed = JsonText.parseStrict(text)) {
                    is JsonParse.Error -> {
                        warnings += Diagnostic.warning(DiagnosticCode.NLS_INVALID, "", "line ${parsed.line}, column ${parsed.column}: ${parsed.message}", path)
                        null
                    }
                    is JsonParse.Ok -> {
                        val obj = parsed.value as? JsonObject
                        if (obj == null) {
                            warnings += Diagnostic.warning(DiagnosticCode.NLS_INVALID, "", "a bundle must be a JSON object", path)
                            null
                        } else path to obj.mapNotNull { (k, v) -> nlsValue(v)?.let { k to it } }.toMap()
                    }
                }
            }
            return Nls(bundles)
        }

        /** VS Code allows `{"message": "...", "comment": [...]}` entries as well as plain strings. */
        private fun nlsValue(v: JsonElement): String? =
            v.stringOrNull ?: (v as? JsonObject)?.get("message")?.stringOrNull
    }
}
