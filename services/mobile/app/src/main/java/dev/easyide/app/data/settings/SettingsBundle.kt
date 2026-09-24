package dev.easyide.app.data.settings

import dev.easyide.app.ui.commands.KeybindingsFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What an export contains; app-level keys are removed before writing (never exported, LLD 15). */
data class ExportContent(
    val appVersion: String,
    val exportedAt: String,
    val userSettings: LayerDoc,
    val keybindings: String,
    val profiles: Map<String, ProfileContent>,
    /** `{id, version, source, scope}` per installed extension; offered for install on import, never auto-installed. */
    val extensions: List<JsonObject>,
)

/** A validated bundle, ready for preview and apply. */
data class ImportBundle(
    val settings: LayerDoc?,
    val keybindings: String?,
    val profiles: Map<String, ProfileContent>,
    val extensions: List<JsonObject>,
    /** Invalid values and keybinding problems: shown in the preview, imported anyway (they are skipped at resolution). */
    val diagnostics: List<SettingsDiagnostic>,
    /** Entries this version does not import (for example snippets, before the snippet engine exists). */
    val ignored: List<String>,
)

/** Why a bundle was refused as a whole. */
class BundleException(message: String) : IOException(message)

/**
 * The export/import zip (sdk-reference: one bundle via SAF, secrets excluded):
 * `manifest.json`, `settings.json` (default profile, `[lang]` blocks included),
 * `keybindings.json`, `profiles/<name>.json`. Environment and project layers are
 * not included: they travel with the environment or the repository. Trust
 * records and capability approvals are never included, so nothing security-
 * relevant is granted on the importing device by a file.
 */
object SettingsBundle {

    const val FORMAT = "easyide-settings"
    const val FORMAT_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val KEYBINDINGS = "keybindings.json"
    const val PROFILES_DIR = "profiles/"
    private const val JSON = ".json"

    fun write(out: OutputStream, content: ExportContent) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST, JsoncEditor.render(manifest(content)))
            put(SETTINGS, JsoncEditor.render(content.userSettings.withoutAppLevel().toJson()))
            put(KEYBINDINGS, content.keybindings)
            content.profiles.toSortedMap().forEach { (name, p) ->
                val clean = p.copy(settings = LayerDoc.fromJson(p.settings).withoutAppLevel().toJson())
                put("$PROFILES_DIR$name$JSON", JsoncEditor.render(clean.toJson()))
            }
        }
    }

    /**
     * Reads and validates a bundle. Refused as a whole: path traversal, absolute
     * or duplicate names, total size over [SettingsPolicy.IMPORT_MAX_BYTES], a
     * missing or foreign manifest, or any file that does not parse. App-level keys
     * in the bundle are dropped: a file must not switch profiles or safe mode.
     */
    fun read(input: InputStream, schema: SchemaState): ImportBundle {
        val files = unzip(input)
        val manifest = files[MANIFEST]?.let(::parseObject) ?: throw BundleException("missing $MANIFEST")
        if (SchemaValidator.stringOrNull(manifest["format"]) != FORMAT ||
            (manifest["formatVersion"] as? JsonPrimitive)?.intOrNull != FORMAT_VERSION
        ) throw BundleException("not an $FORMAT v$FORMAT_VERSION bundle")

        val diagnostics = ArrayList<SettingsDiagnostic>()
        val settings = files[SETTINGS]?.let { text ->
            diagnostics += SettingsJsonDiagnostics.check(text, LayerId.USER, schema).filter { it.severity == Severity.ERROR }
            LayerDoc.fromJson(parseObject(text)).withoutAppLevel()
        }
        val keybindings = files[KEYBINDINGS]?.also { text ->
            val parsed = KeybindingsFile.parse(text)
            if (parsed.diagnostics.any { it.code == DiagnosticCode.PARSE_ERROR }) throw BundleException("$KEYBINDINGS does not parse")
            diagnostics += parsed.diagnostics
        }
        val profiles = LinkedHashMap<String, ProfileContent>()
        val ignored = ArrayList<String>()
        for ((name, text) in files) {
            if (name == MANIFEST || name == SETTINGS || name == KEYBINDINGS) continue
            val profile = name.removePrefix(PROFILES_DIR).removeSuffix(JSON)
            val isProfile = name.startsWith(PROFILES_DIR) && name.endsWith(JSON) &&
                SettingsPolicy.PROFILE_NAME.matches(profile) && profile != SettingsPolicy.DEFAULT_PROFILE
            if (!isProfile) { ignored += name; continue }
            val content = ProfileContent.parse(text).getOrElse { throw BundleException("$name is not a valid profile") }
            profiles[profile] = content.copy(settings = LayerDoc.fromJson(content.settings).withoutAppLevel().toJson())
        }
        val extensions = (manifest["extensions"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        return ImportBundle(settings, keybindings, profiles, extensions, diagnostics, ignored)
    }

    private fun manifest(c: ExportContent): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "format" to JsonPrimitive(FORMAT),
            "formatVersion" to JsonPrimitive(FORMAT_VERSION),
            "appVersion" to JsonPrimitive(c.appVersion),
            "exportedAt" to JsonPrimitive(c.exportedAt),
            "profiles" to JsonArray(c.profiles.keys.sorted().map(::JsonPrimitive)),
            "extensions" to JsonArray(c.extensions),
        ),
    )

    private fun parseObject(text: String): JsonObject =
        ((Jsonc.parse(text) as? JsoncResult.Ok)?.root?.value as? JsonObject) ?: throw BundleException("invalid JSON in bundle")

    /** Boundary: untrusted archive. Sizes are counted as bytes are inflated, not taken from headers. */
    private fun unzip(input: InputStream): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                if (!isSafePath(name)) throw BundleException("unsafe path in bundle: $name")
                if (name in files) throw BundleException("duplicate entry in bundle: $name")
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > SettingsPolicy.IMPORT_MAX_BYTES) throw BundleException("bundle larger than ${SettingsPolicy.IMPORT_MAX_BYTES} bytes")
                    bytes.write(buffer, 0, n)
                }
                files[name] = bytes.toString(Charsets.UTF_8.name())
            }
        }
        return files
    }

    /** The package rules: relative, no `..` segment, no backslashes or drive letters. */
    fun isSafePath(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && ':' !in name &&
            name.split('/').none { it == ".." || it == "." || it.isEmpty() }

    private const val BUFFER_BYTES = 8 * 1024
}
