package dev.easyide.app.extensions.install

import dev.easyide.extensions.AppApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException

/**
 * Lets a picked VS Code extension (`.vsix` from Open VSX, or its unpacked folder) install when
 * it contributes icon themes: the theme file and its SVG/PNG icons are read as they are, so the
 * only translation is the manifest. Runs on the unpacked tree before validation, so everything
 * that is not an icon theme (commands, colour themes, settings, code) is dropped and the result
 * goes through the same checks as any pack. Packages that already declare `engines.easyide`, or
 * contribute no icon theme, are left alone.
 */
object VsCodeIconThemeAdapter {

    /** Top-level entries of a `.vsix` that are not part of the extension. */
    private const val VSIX_ROOT = "extension"

    /**
     * Rewrites [dir] in place when it holds such an extension.
     * @return true when it was converted.
     * @throws IOException when a file cannot be moved.
     */
    fun adapt(dir: File): Boolean {
        val root = File(dir, VSIX_ROOT).takeIf { File(it, "package.json").isFile } ?: dir
        val manifest = read(File(root, "package.json")) ?: return false
        val engines = manifest["engines"] as? JsonObject
        if (engines == null || "vscode" !in engines || "easyide" in engines) return false
        val themes = (manifest["contributes"] as? JsonObject)?.get("iconThemes") as? JsonArray ?: return false
        val kept = themes.filterIsInstance<JsonObject>().filter { t -> listOf("id", "label", "path").all { it.text(t) != null } }
        if (kept.isEmpty()) return false
        if (root != dir) lift(root, dir)
        File(dir, "package.json").writeText(Json.encodeToString(JsonObject.serializer(), converted(manifest, kept)))
        return true
    }

    private fun converted(m: JsonObject, themes: List<JsonObject>): JsonObject = buildJsonObject {
        // Marketplace ids are case-insensitive (`PKief.material-icon-theme`); easyIDE ids are lower case.
        for (key in listOf("name", "publisher")) text(m[key])?.let { put(key, it.lowercase()) }
        for (key in listOf("version", "displayName", "description", "license")) m[key]?.let { put(key, it) }
        putJsonObject("engines") { put("easyide", "^${AppApi.VERSION}") }
        putJsonArray("categories") { add(JsonPrimitive("Themes")) }
        putJsonObject("contributes") {
            put("iconThemes", buildJsonArray {
                themes.forEach { t -> add(buildJsonObject { for (k in listOf("id", "label", "path")) put(k, t.getValue(k)) }) }
            })
        }
    }

    /** Moves the extension folder's content up over [dir]'s, dropping the `.vsix` envelope files around it. */
    private fun lift(from: File, dir: File) {
        val keep = from.listFiles().orEmpty().toList()
        dir.listFiles().orEmpty().filter { it != from }.forEach { it.deleteRecursively() }
        for (f in keep) if (!f.renameTo(File(dir, f.name))) throw IOException("cannot move ${f.name}")
        from.delete()
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun String.text(o: JsonObject): String? = text(o[this])

    /** I/O boundary: a `package.json` that does not read or parse is not an extension to adapt. */
    private fun read(f: File): JsonObject? = try {
        if (f.isFile) Json.parseToJsonElement(f.readText()) as? JsonObject else null
    } catch (e: IOException) {
        null
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }
}
