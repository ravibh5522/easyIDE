package dev.easyide.app.extensions.fileicons

import dev.easyide.app.extensions.adapters.IconTheme
import dev.easyide.app.extensions.adapters.IconThemeFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The generated `easyide.file-icons` pack as tests see it: files on disk, the raw theme JSON, and the parsed theme. */
object FileIconsPack {
    val root = File("src/main/assets/extensions/easyide.file-icons")
    val themeFile = File(root, "icons/easyide-file-icons.json")
    val raw: JsonObject = Json.parseToJsonElement(themeFile.readText()).jsonObject
    val theme: IconTheme = IconThemeFile.parse("easyide-file-icons", themeFile.readText(), themeFile, root)!!

    fun table(name: String, section: JsonObject = raw): Map<String, String> =
        (section[name] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    val light: JsonObject get() = raw["light"] as JsonObject

    /** Icon id -> SVG file, for every definition. */
    val definitions: Map<String, File> = (raw["iconDefinitions"] as JsonObject).mapValues { (_, v) ->
        File(themeFile.parentFile, v.jsonObject["iconPath"]!!.jsonPrimitive.content).canonicalFile
    }
}
