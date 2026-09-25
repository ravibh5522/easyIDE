package dev.easyide.app.extensions.materialicons

import dev.easyide.app.extensions.adapters.IconTheme
import dev.easyide.app.extensions.adapters.IconThemeFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The vendored `easyide.material-icons` pack as tests see it: files on disk, the raw theme JSON, the parsed theme. */
object MaterialIconsPack {
    const val THEME_ID = "material-icon-theme"
    val root = File("src/main/assets/extensions/easyide.material-icons")
    val themeFile = File(root, "dist/material-icons.json")
    val raw: JsonObject = Json.parseToJsonElement(themeFile.readText()).jsonObject
    val theme: IconTheme = IconThemeFile.parse(THEME_ID, themeFile.readText(), themeFile, root)!!

    private val singles = listOf("file", "folder", "folderExpanded", "rootFolder", "rootFolderExpanded")
    private val tables = listOf("fileNames", "fileExtensions", "languageIds", "folderNames", "folderNamesExpanded")

    /** Icon id -> SVG file, for every definition. */
    val definitions: Map<String, File> = (raw["iconDefinitions"] as JsonObject).mapValues { (_, v) ->
        File(themeFile.parentFile, v.jsonObject["iconPath"]!!.jsonPrimitive.content).canonicalFile
    }

    /** Every icon id a mapping of the root, `light` or `highContrast` section points at, with where it is mapped from. */
    fun references(): List<Pair<String, String>> = listOf("" to raw, "light." to (raw["light"] as JsonObject), "highContrast." to (raw["highContrast"] as JsonObject)).flatMap { (prefix, section) ->
        singles.mapNotNull { k -> section[k]?.jsonPrimitive?.content?.let { "$prefix$k" to it } } +
            tables.flatMap { t -> (section[t] as? JsonObject).orEmpty().map { (k, v) -> "$prefix$t[$k]" to v.jsonPrimitive.content } }
    }
}
