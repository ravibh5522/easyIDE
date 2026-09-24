package dev.easyide.extensions.manifest

import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Phase 11: referenced JSON content files parse and have the shape their point needs, so
 * "validates" means "the app can load it". JSONC (comments, trailing commas) is accepted
 * because VS Code's own theme and language-configuration files use it. Plist grammars are
 * parsed by the app's GrammarReader, which is not part of this module.
 */
internal class ContentChecks(private val ctx: DecodeContext) {

    fun run(c: Contributions) {
        c.grammars.filter { isJson(it.file) }.forEach { g ->
            read(g.file)?.let { v ->
                val obj = requireObject(g.file, v) ?: return@let
                val declared = obj["scopeName"]?.stringOrNull
                if (declared != null && declared != g.scopeName) {
                    warn(g.file, "/scopeName", "grammar declares '$declared' but the manifest says '${g.scopeName}'")
                }
            }
        }
        c.themes.forEach { t -> read(t.file)?.let { checkTheme(t.file, it) } }
        c.snippets.forEach { s -> read(s.file)?.let { checkSnippets(s.file, it) } }
        c.languageConfigurations.forEach { l -> read(l.file)?.let { requireObject(l.file, it) } }
        c.iconThemes.forEach { t -> read(t.file)?.let { checkIconTheme(t.file, it) } }
    }

    private fun checkTheme(f: PackageFile, v: JsonElement) {
        val obj = requireObject(f, v) ?: return
        obj["colors"]?.let { if (it !is JsonObject) error(f, "/colors", "must be an object of colour keys") }
        obj["tokenColors"]?.let { tc ->
            when {
                tc is JsonArray -> Unit
                tc.stringOrNull != null -> ctx.file(resolveRelative(f, tc.stringOrNull!!), "/contributes/themes")
                else -> error(f, "/tokenColors", "must be an array of rules or a path to a .tmTheme file")
            }
        }
    }

    private fun checkSnippets(f: PackageFile, v: JsonElement) {
        val obj = requireObject(f, v) ?: return
        for ((name, entry) in obj) {
            val p = JsonPointer.child("", name)
            val e = entry as? JsonObject
            if (e == null) { error(f, p, "a snippet must be an object"); continue }
            val body = e["body"]
            if (body == null || !(body.stringOrNull != null || (body is JsonArray && body.all { it.stringOrNull != null }))) {
                error(f, JsonPointer.child(p, "body"), "body must be a string or an array of strings")
            }
        }
    }

    private fun checkIconTheme(f: PackageFile, v: JsonElement) {
        val obj = requireObject(f, v) ?: return
        if ("fonts" in obj) ctx.diagnostics += Diagnostic.warning(DiagnosticCode.CONTENT_IGNORED, "/fonts", "icon fonts are not supported; SVG/PNG only", f.path)
        (obj["iconDefinitions"] as? JsonObject)?.forEach { (name, def) ->
            val iconPath = (def as? JsonObject)?.get("iconPath")?.stringOrNull ?: return@forEach
            val p = JsonPointer.child(JsonPointer.child("/iconDefinitions", name), "iconPath")
            val lower = iconPath.lowercase()
            if (!lower.endsWith(".svg") && !lower.endsWith(".png")) {
                error(f, p, "icons must be SVG or PNG")
                return@forEach
            }
            val path = PackagePaths.normalize(resolveRelative(f, iconPath))
            if (path == null || !ctx.files.exists(path)) error(f, p, "'$iconPath' does not exist in the package")
            else ctx.referenced += path
        }
    }

    /** Resolves [ref] against the directory of [base], the way VS Code resolves theme-relative paths. */
    private fun resolveRelative(base: PackageFile, ref: String): String {
        val dir = base.path.substringBeforeLast('/', "")
        return if (dir.isEmpty()) ref else "$dir/$ref"
    }

    /** I/O boundary: an unreadable file is an E_CONTENT error for that file. */
    private fun read(f: PackageFile): JsonElement? {
        val text = try { ctx.files.read(f.path).decodeToString() } catch (e: IOException) {
            error(f, "", "cannot read: ${e.message}")
            return null
        }
        return when (val r = JsonText.parseLenient(text)) {
            is JsonParse.Ok -> r.value
            is JsonParse.Error -> { error(f, "", "line ${r.line}, column ${r.column}: ${r.message}"); null }
        }
    }

    private fun requireObject(f: PackageFile, v: JsonElement): JsonObject? =
        (v as? JsonObject) ?: run { error(f, "", "must be a JSON object"); null }

    private fun isJson(f: PackageFile) = f.path.lowercase().endsWith(".json")

    private fun error(f: PackageFile, pointer: String, message: String) {
        ctx.diagnostics += Diagnostic.error(DiagnosticCode.CONTENT, pointer, message, f.path)
    }

    private fun warn(f: PackageFile, pointer: String, message: String) {
        ctx.diagnostics += Diagnostic.warning(DiagnosticCode.CONTENT_IGNORED, pointer, message, f.path)
    }
}
