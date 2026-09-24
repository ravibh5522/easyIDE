package dev.easyide.extensions.schema

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The bundled `builtin-commands.json`: command ids the app implements natively, which
 * manifests may reference without declaring (`ParseOptions.builtInCommands`). Part of the
 * extension API, so `easyide-ext validate` resolves them exactly as the app does.
 */
object BuiltInCommands {
    const val RESOURCE = "/builtin-commands.json"

    /** A missing or malformed resource is a build defect, so it fails loudly. */
    val IDS: Set<String> by lazy {
        val text = checkNotNull(BuiltInCommands::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE not packaged" }
            .use { it.readBytes().decodeToString() }
        val root = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject ?: error("$RESOURCE is not a JSON object")
        val list = root["commands"] as? JsonArray ?: error("$RESOURCE has no commands array")
        list.map { it.stringOrNull ?: error("$RESOURCE: command ids are strings") }.toSet()
    }
}
