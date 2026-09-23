package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.bool
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.isNullish
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.str
import dev.easyide.lsp.json.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Feature ids, verbatim from sdk-reference `easyide.languageServers` `features`. */
enum class LspFeature(val id: String) {
    DIAGNOSTICS("diagnostics"), COMPLETION("completion"), HOVER("hover"), SIGNATURE_HELP("signatureHelp"),
    DEFINITION("definition"), DECLARATION("declaration"), TYPE_DEFINITION("typeDefinition"),
    IMPLEMENTATION("implementation"), REFERENCES("references"), DOCUMENT_HIGHLIGHT("documentHighlight"),
    DOCUMENT_SYMBOL("documentSymbol"), WORKSPACE_SYMBOL("workspaceSymbol"), RENAME("rename"),
    CODE_ACTION("codeAction"), CODE_LENS("codeLens"), FORMATTING("formatting"),
    RANGE_FORMATTING("rangeFormatting"), ON_TYPE_FORMATTING("onTypeFormatting"), INLAY_HINTS("inlayHints"),
    SEMANTIC_TOKENS("semanticTokens"), FOLDING_RANGE("foldingRange"), SELECTION_RANGE("selectionRange"),
    DOCUMENT_LINK("documentLink");

    companion object {
        /** Unknown ids (typos, newer manifests) are null; the config layer warns about them. */
        fun fromId(id: String): LspFeature? = entries.firstOrNull { it.id == id }
    }
}

enum class SyncKind(val wire: Int) {
    NONE(0), FULL(1), INCREMENTAL(2);

    companion object {
        fun fromWire(v: Int?): SyncKind = entries.firstOrNull { it.wire == v } ?: NONE
    }
}

/**
 * Resolved `textDocumentSync`. The number form means open/close plus that change kind and
 * `didSave` without text, the same resolution vscode-languageclient applies.
 */
data class SyncOptions(val openClose: Boolean, val change: SyncKind, val save: Boolean, val saveIncludesText: Boolean, val willSaveWaitUntil: Boolean) {
    companion object {
        val NONE = SyncOptions(openClose = false, change = SyncKind.NONE, save = false, saveIncludesText = false, willSaveWaitUntil = false)

        fun fromJson(e: JsonElement?): SyncOptions {
            e.int?.let { kind ->
                val k = SyncKind.fromWire(kind)
                return if (k == SyncKind.NONE) NONE else SyncOptions(true, k, save = true, saveIncludesText = false, willSaveWaitUntil = false)
            }
            val o = e.obj ?: return NONE
            val save = o["save"]
            return SyncOptions(
                openClose = o["openClose"].bool == true,
                change = SyncKind.fromWire(o["change"].int),
                save = save.bool == true || save.obj != null,
                saveIncludesText = save.obj?.get("includeText").bool == true,
                willSaveWaitUntil = o["willSaveWaitUntil"].bool == true,
            )
        }
    }
}

/**
 * The parts of the server's capabilities the client acts on. [raw] keeps the whole object for
 * extension access (`lsp.status`) and for fields a later milestone reads.
 */
data class ServerCapabilities(
    val positionEncoding: String?,
    val sync: SyncOptions,
    val completionTriggerCharacters: List<String>,
    val completionResolve: Boolean,
    val signatureTriggerCharacters: List<String>,
    val signatureRetriggerCharacters: List<String>,
    val onTypeFormattingTriggers: List<String>,
    val prepareRename: Boolean,
    val codeActionResolve: Boolean,
    val codeLensResolve: Boolean,
    val documentLinkResolve: Boolean,
    val inlayHintResolve: Boolean,
    val semanticTokensLegend: SemanticTokensLegend?,
    val semanticTokensFull: Boolean,
    val semanticTokensDelta: Boolean,
    val semanticTokensRange: Boolean,
    val pullDiagnostics: Boolean,
    val diagnosticIdentifier: String?,
    val executeCommands: List<String>,
    val raw: JsonObject,
) {
    /** Whether the server advertised [feature] statically (dynamic registration is not offered for these). */
    fun supports(feature: LspFeature): Boolean = when (feature) {
        // Push diagnostics need no capability: any server may publish.
        LspFeature.DIAGNOSTICS -> true
        LspFeature.COMPLETION -> enabled("completionProvider")
        LspFeature.HOVER -> enabled("hoverProvider")
        LspFeature.SIGNATURE_HELP -> enabled("signatureHelpProvider")
        LspFeature.DEFINITION -> enabled("definitionProvider")
        LspFeature.DECLARATION -> enabled("declarationProvider")
        LspFeature.TYPE_DEFINITION -> enabled("typeDefinitionProvider")
        LspFeature.IMPLEMENTATION -> enabled("implementationProvider")
        LspFeature.REFERENCES -> enabled("referencesProvider")
        LspFeature.DOCUMENT_HIGHLIGHT -> enabled("documentHighlightProvider")
        LspFeature.DOCUMENT_SYMBOL -> enabled("documentSymbolProvider")
        LspFeature.WORKSPACE_SYMBOL -> enabled("workspaceSymbolProvider")
        LspFeature.RENAME -> enabled("renameProvider")
        LspFeature.CODE_ACTION -> enabled("codeActionProvider")
        LspFeature.CODE_LENS -> enabled("codeLensProvider")
        LspFeature.FORMATTING -> enabled("documentFormattingProvider")
        LspFeature.RANGE_FORMATTING -> enabled("documentRangeFormattingProvider")
        LspFeature.ON_TYPE_FORMATTING -> onTypeFormattingTriggers.isNotEmpty()
        LspFeature.INLAY_HINTS -> enabled("inlayHintProvider")
        LspFeature.SEMANTIC_TOKENS -> semanticTokensLegend != null && (semanticTokensFull || semanticTokensRange)
        LspFeature.FOLDING_RANGE -> enabled("foldingRangeProvider")
        LspFeature.SELECTION_RANGE -> enabled("selectionRangeProvider")
        LspFeature.DOCUMENT_LINK -> enabled("documentLinkProvider")
    }

    /** `true` or an options object enables a provider; `false`, `null` or absent does not. */
    private fun enabled(key: String): Boolean {
        val v = raw[key]
        if (v.isNullish) return false
        return v.bool ?: (v.obj != null)
    }

    companion object {
        fun fromJson(o: JsonObject): ServerCapabilities {
            val completion = o["completionProvider"].obj
            val signature = o["signatureHelpProvider"].obj
            val onType = o["documentOnTypeFormattingProvider"].obj
            val semantic = o["semanticTokensProvider"].obj
            val full = semantic?.get("full")
            val diagnostic = o["diagnosticProvider"].obj
            return ServerCapabilities(
                positionEncoding = o["positionEncoding"].str,
                sync = SyncOptions.fromJson(o["textDocumentSync"]),
                completionTriggerCharacters = completion?.get("triggerCharacters").strings,
                completionResolve = completion?.get("resolveProvider").bool == true,
                signatureTriggerCharacters = signature?.get("triggerCharacters").strings,
                signatureRetriggerCharacters = signature?.get("retriggerCharacters").strings,
                onTypeFormattingTriggers = onType?.let { listOfNotNull(it["firstTriggerCharacter"].str) + it["moreTriggerCharacter"].strings }.orEmpty(),
                prepareRename = o["renameProvider"].obj?.get("prepareProvider").bool == true,
                codeActionResolve = o["codeActionProvider"].obj?.get("resolveProvider").bool == true,
                codeLensResolve = o["codeLensProvider"].obj?.get("resolveProvider").bool == true,
                documentLinkResolve = o["documentLinkProvider"].obj?.get("resolveProvider").bool == true,
                inlayHintResolve = o["inlayHintProvider"].obj?.get("resolveProvider").bool == true,
                semanticTokensLegend = SemanticTokensLegend.fromJson(semantic?.get("legend")),
                semanticTokensFull = full.bool == true || full.obj != null,
                semanticTokensDelta = full.obj?.get("delta").bool == true,
                semanticTokensRange = semantic?.get("range").let { it.bool == true || it.obj != null },
                pullDiagnostics = diagnostic != null,
                diagnosticIdentifier = diagnostic?.get("identifier").str,
                executeCommands = o["executeCommandProvider"].obj?.get("commands").strings,
                raw = o,
            )
        }
    }
}
