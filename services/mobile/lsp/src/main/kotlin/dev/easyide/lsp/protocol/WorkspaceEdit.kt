package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.bool
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonElement

/**
 * A `WorkspaceEdit`, normalised to one ordered list of operations whether the server sent
 * `changes` (a map, no versions, no resource operations) or `documentChanges` (ordered, may
 * carry versions and create/rename/delete). When both are present `documentChanges` wins,
 * as the spec requires.
 */
data class WorkspaceEdit(val operations: List<EditOperation>) {
    companion object {
        fun fromJson(e: JsonElement?): WorkspaceEdit? {
            val o = e.obj ?: return null
            val documentChanges = o["documentChanges"]
            if (documentChanges != null) {
                return WorkspaceEdit(documentChanges.mapItems(::operationFromJson))
            }
            val changes = o["changes"].obj ?: return WorkspaceEdit(emptyList())
            return WorkspaceEdit(changes.map { (uri, edits) -> EditOperation.Text(uri, null, TextEdit.listFromJson(edits)) })
        }

        private fun operationFromJson(e: JsonElement): EditOperation? {
            val o = e.obj ?: return null
            val options = o["options"].obj
            return when (o["kind"].str) {
                "create" -> EditOperation.Create(
                    uri = o["uri"].str ?: return null,
                    overwrite = options?.get("overwrite").bool ?: false,
                    ignoreIfExists = options?.get("ignoreIfExists").bool ?: false,
                )
                "rename" -> EditOperation.Rename(
                    oldUri = o["oldUri"].str ?: return null,
                    newUri = o["newUri"].str ?: return null,
                    overwrite = options?.get("overwrite").bool ?: false,
                    ignoreIfExists = options?.get("ignoreIfExists").bool ?: false,
                )
                "delete" -> EditOperation.Delete(
                    uri = o["uri"].str ?: return null,
                    recursive = options?.get("recursive").bool ?: false,
                    ignoreIfNotExists = options?.get("ignoreIfNotExists").bool ?: false,
                )
                null -> {
                    val doc = o["textDocument"].obj ?: return null
                    // Annotated edits carry `annotationId`; the text is what matters here
                    // (change annotations are not advertised, arch lsp-features.md sec 2).
                    EditOperation.Text(doc["uri"].str ?: return null, doc["version"].int, TextEdit.listFromJson(o["edits"]))
                }
                else -> null
            }
        }
    }
}

sealed interface EditOperation {
    /** [version] null means "any version" (from `changes`, or a null `OptionalVersioned`). */
    data class Text(val uri: String, val version: Int?, val edits: List<TextEdit>) : EditOperation
    data class Create(val uri: String, val overwrite: Boolean, val ignoreIfExists: Boolean) : EditOperation
    data class Rename(val oldUri: String, val newUri: String, val overwrite: Boolean, val ignoreIfExists: Boolean) : EditOperation
    data class Delete(val uri: String, val recursive: Boolean, val ignoreIfNotExists: Boolean) : EditOperation
}
