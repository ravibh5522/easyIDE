package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class DiagnosticSeverity(val wire: Int) {
    ERROR(1), WARNING(2), INFORMATION(3), HINT(4);

    companion object {
        fun fromWire(v: Int?): DiagnosticSeverity? = entries.firstOrNull { it.wire == v }
    }
}

enum class DiagnosticTag(val wire: Int) {
    UNNECESSARY(1), DEPRECATED(2);

    companion object {
        fun fromWire(v: Int?): DiagnosticTag? = entries.firstOrNull { it.wire == v }
    }
}

data class RelatedInformation(val location: Location, val message: String)

/**
 * One diagnostic. [code] is kept as text whether the server sent a number (pyright rule
 * numbers, tsserver `2304`) or a string (`reportMissingImports`). A missing severity stays
 * null: the spec leaves it to the client, and filters treat it as [DiagnosticSeverity.ERROR].
 *
 * [raw] is the object as received: `codeAction` context must echo diagnostics back verbatim,
 * including `data`, which the server uses to rebuild its fix.
 */
data class Diagnostic(
    val range: Range,
    val severity: DiagnosticSeverity?,
    val code: String?,
    val codeHref: String?,
    val source: String?,
    val message: String,
    val tags: Set<DiagnosticTag>,
    val related: List<RelatedInformation>,
    val raw: JsonObject,
) {
    companion object {
        fun fromJson(e: JsonElement?): Diagnostic? {
            val o = e.obj ?: return null
            val code = (o["code"] as? JsonPrimitive)?.content
            return Diagnostic(
                range = Range.fromJson(o["range"]) ?: return null,
                severity = DiagnosticSeverity.fromWire(o["severity"].int),
                code = code,
                codeHref = o["codeDescription"].obj?.get("href").str,
                source = o["source"].str,
                message = o["message"].str ?: return null,
                tags = o["tags"].mapItems { DiagnosticTag.fromWire(it.int) }.toSet(),
                related = o["relatedInformation"].mapItems { r ->
                    val ro = r.obj ?: return@mapItems null
                    RelatedInformation(Location.fromJson(ro["location"]) ?: return@mapItems null, ro["message"].str.orEmpty())
                },
                raw = o,
            )
        }

        fun listFromJson(e: JsonElement?): List<Diagnostic> = e.mapItems(::fromJson)
    }
}

/** `textDocument/publishDiagnostics` params. */
data class PublishDiagnostics(val uri: String, val version: Int?, val diagnostics: List<Diagnostic>) {
    companion object {
        fun fromJson(e: JsonElement?): PublishDiagnostics? {
            val o = e.obj ?: return null
            return PublishDiagnostics(o["uri"].str ?: return null, o["version"].int, Diagnostic.listFromJson(o["diagnostics"]))
        }
    }
}

/** `textDocument/diagnostic` result (pull model). */
sealed interface DocumentDiagnosticReport {
    val resultId: String?

    data class Full(override val resultId: String?, val items: List<Diagnostic>) : DocumentDiagnosticReport
    data class Unchanged(override val resultId: String) : DocumentDiagnosticReport

    companion object {
        fun fromJson(e: JsonElement?): DocumentDiagnosticReport? {
            val o = e.obj ?: return null
            return when (o["kind"].str) {
                "unchanged" -> Unchanged(o["resultId"].str ?: return null)
                "full" -> Full(o["resultId"].str, Diagnostic.listFromJson(o["items"]))
                else -> null
            }
        }
    }
}
