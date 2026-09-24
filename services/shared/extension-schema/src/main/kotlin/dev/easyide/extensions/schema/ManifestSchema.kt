package dev.easyide.extensions.schema

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.manifest.Severity
import kotlinx.serialization.json.JsonObject

/** The bundled `manifest.schema.json` (services/shared/extension-schema), loaded once. */
object ManifestSchema {
    const val RESOURCE = "/manifest.schema.json"

    /** The schema tree. A missing or malformed resource is a build defect, so it fails loudly. */
    val json: JsonObject by lazy {
        val text = checkNotNull(ManifestSchema::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE not packaged" }
            .use { it.readBytes().decodeToString() }
        when (val r = JsonText.parseStrict(text)) {
            is JsonParse.Ok -> r.value as JsonObject
            is JsonParse.Error -> error("$RESOURCE line ${r.line}: ${r.message}")
        }
    }

    /** Manifest validator: unknown keys warn (R-API-08). */
    val validator: SchemaValidator by lazy { SchemaValidator.create(json, unknownPropertySeverity = Severity.WARNING) }
}
