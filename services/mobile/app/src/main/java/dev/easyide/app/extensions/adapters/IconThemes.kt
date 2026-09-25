package dev.easyide.app.extensions.adapters

import dev.easyide.app.data.settings.Jsonc
import dev.easyide.app.data.settings.JsoncResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** One section of a VS Code file icon theme: the associations of icon ids to names (the root, or `light`). */
data class IconAssociations(
    val file: String? = null,
    val folder: String? = null,
    val folderExpanded: String? = null,
    val rootFolder: String? = null,
    val rootFolderExpanded: String? = null,
    /** Lower-cased keys. */
    val fileNames: Map<String, String> = emptyMap(),
    /** Lower-cased keys, without the leading dot (`d.ts`, `ts`). */
    val fileExtensions: Map<String, String> = emptyMap(),
    val languageIds: Map<String, String> = emptyMap(),
    val folderNames: Map<String, String> = emptyMap(),
    val folderNamesExpanded: Map<String, String> = emptyMap(),
)

/**
 * A parsed icon theme (customization.md sec 9): icon id -> image file on the host, plus the
 * root associations and the optional `light` overrides. Font icons (`fontCharacter`, `fonts`)
 * are not supported and those definitions are dropped.
 */
data class IconTheme(
    val id: String,
    val icons: Map<String, String>,
    val dark: IconAssociations,
    val light: IconAssociations?,
) {
    /**
     * The image for a file, first hit wins: `fileNames` (case-insensitive) > `fileExtensions`
     * (longest multi-part first, `d.ts` before `ts`) > `languageIds` > `file`. With [light], the
     * `light` section is asked before the root section at each step.
     */
    fun fileIcon(fileName: String, languageId: String?, light: Boolean): String? {
        val name = fileName.lowercase()
        val sections = sections(light)
        sections.firstNotNullOfOrNull { it.fileNames[name] }?.let(icons::get)?.let { return it }
        for (ext in extensionsOf(name)) sections.firstNotNullOfOrNull { it.fileExtensions[ext] }?.let(icons::get)?.let { return it }
        languageId?.let { lang -> sections.firstNotNullOfOrNull { it.languageIds[lang] } }?.let(icons::get)?.let { return it }
        return sections.firstNotNullOfOrNull { it.file }?.let(icons::get)
    }

    /** The image for a folder: `folderNames`/`folderNamesExpanded` > `folder`/`folderExpanded`. */
    fun folderIcon(folderName: String, expanded: Boolean, light: Boolean): String? {
        val name = folderName.lowercase()
        val sections = sections(light)
        val byName = sections.firstNotNullOfOrNull { s -> if (expanded) s.folderNamesExpanded[name] ?: s.folderNames[name] else s.folderNames[name] }
        byName?.let(icons::get)?.let { return it }
        val generic = sections.firstNotNullOfOrNull { s -> if (expanded) s.folderExpanded ?: s.folder else s.folder }
        return generic?.let(icons::get)
    }

    /** The workspace root's image: `rootFolder`/`rootFolderExpanded`, then the generic folder. */
    fun rootFolderIcon(expanded: Boolean, light: Boolean): String? {
        val sections = sections(light)
        val root = sections.firstNotNullOfOrNull { s -> if (expanded) s.rootFolderExpanded ?: s.rootFolder else s.rootFolder }
        return root?.let(icons::get) ?: folderIcon("", expanded, light)
    }

    private fun sections(light: Boolean): List<IconAssociations> = if (light && this.light != null) listOf(this.light, dark) else listOf(dark)

    companion object {
        /** `a.test.d.ts` -> `test.d.ts`, `d.ts`, `ts`: every multi-part suffix, longest first. */
        fun extensionsOf(name: String): List<String> {
            val parts = name.split('.')
            if (parts.size < 2) return emptyList()
            return (1 until parts.size).map { parts.drop(it).joinToString(".") }.filter { it.isNotEmpty() }
        }
    }
}

/** Reads a contributed icon theme file (JSON with comments, as VS Code accepts). */
object IconThemeFile {

    /** Image formats the app can draw: PNG through the platform decoder, SVG through [SvgParser]'s subset. */
    val SUPPORTED_EXTENSIONS = setOf("png", "svg")

    /**
     * Parses [text] of the theme file at [themeFile]; icon paths are resolved against the file's
     * directory and must stay inside [extensionRoot] (a path escaping it is dropped, like a
     * missing file). [unsupported] receives each icon skipped for its format, once.
     *
     * @return null when the text is not a JSON object.
     */
    fun parse(id: String, text: String, themeFile: File, extensionRoot: File, unsupported: (String) -> Unit = {}): IconTheme? {
        val root = (Jsonc.parse(text) as? JsoncResult.Ok)?.root?.value as? JsonObject ?: return null
        val base = themeFile.parentFile ?: return null
        val rootPath = extensionRoot.canonicalFile
        val icons = LinkedHashMap<String, String>()
        (root["iconDefinitions"] as? JsonObject)?.forEach { (iconId, def) ->
            val rel = ((def as? JsonObject)?.get("iconPath") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            val file = File(base, rel).canonicalFile
            if (!file.path.startsWith(rootPath.path + File.separator)) return@forEach
            if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) return@forEach unsupported(rel)
            icons[iconId] = file.path
        }
        return IconTheme(id, icons, associations(root), (root["light"] as? JsonObject)?.let(::associations))
    }

    private fun associations(o: JsonObject) = IconAssociations(
        file = o.str("file"),
        folder = o.str("folder"),
        folderExpanded = o.str("folderExpanded"),
        rootFolder = o.str("rootFolder"),
        rootFolderExpanded = o.str("rootFolderExpanded"),
        fileNames = o.map("fileNames"),
        fileExtensions = o.map("fileExtensions").mapKeys { it.key.removePrefix(".") },
        languageIds = o.map("languageIds", lowercase = false),
        folderNames = o.map("folderNames"),
        folderNamesExpanded = o.map("folderNamesExpanded"),
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.map(key: String, lowercase: Boolean = true): Map<String, String> =
        (this[key] as? JsonObject)?.mapNotNull { (k, v: JsonElement) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { (if (lowercase) k.lowercase() else k) to it }
        }?.toMap().orEmpty()
}
