package dev.easyide.lsp.protocol

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.json.jsonInts
import dev.easyide.lsp.json.jsonStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Delivery milestones that change what the client advertises (arch.md 5.2). */
enum class Milestone { M2, M4 }

/** Editor decoration layers (PLT-05, ADR-B). A capability whose layer is missing is not advertised. */
enum class UiLayer { UNDERLINE, BACKGROUND_RANGE, INLINE_TEXT, GUTTER_ICON, BETWEEN_LINE_BLOCK, CARET_POPUP, TOKEN_OVERLAY }

/**
 * What the app can render right now.
 *
 * @property semanticTokenTypes / [semanticTokenModifiers] the names `SemanticRules` maps to
 *   roles; advertised verbatim so servers only send types the renderer can colour.
 * @property withheld features whose presenter this app build does not ship even though their
 *   milestone is reached and no layer is missing (a panel- or command-only feature such as
 *   folding or selection ranges). Never advertised, so a server does no work nobody shows.
 */
data class ClientUi(
    val layers: Set<UiLayer>,
    val semanticTokenTypes: List<String>,
    val semanticTokenModifiers: List<String>,
    val withheld: Set<LspFeature> = emptySet(),
)

/**
 * Builds `initialize.capabilities` exactly per lsp-features.md sec 2: a capability is present
 * only when the UI that renders it exists at the milestone (arch.md 7.5), so a server never
 * does work the client would discard.
 */
object ClientCapabilitiesBuilder {

    private val M2_FEATURES = setOf(
        LspFeature.DIAGNOSTICS, LspFeature.COMPLETION, LspFeature.HOVER, LspFeature.SIGNATURE_HELP,
        LspFeature.DEFINITION, LspFeature.DECLARATION, LspFeature.TYPE_DEFINITION, LspFeature.IMPLEMENTATION,
        LspFeature.REFERENCES, LspFeature.DOCUMENT_HIGHLIGHT, LspFeature.DOCUMENT_SYMBOL, LspFeature.RENAME,
        LspFeature.CODE_ACTION, LspFeature.FORMATTING, LspFeature.RANGE_FORMATTING, LspFeature.ON_TYPE_FORMATTING,
    )

    private val M4_FEATURES = setOf(
        LspFeature.WORKSPACE_SYMBOL, LspFeature.INLAY_HINTS, LspFeature.SEMANTIC_TOKENS, LspFeature.FOLDING_RANGE,
        LspFeature.SELECTION_RANGE, LspFeature.CODE_LENS, LspFeature.DOCUMENT_LINK,
    )

    /** The decoration layer each feature needs; features absent here render in panels or edits. */
    private val REQUIRED_LAYER = mapOf(
        LspFeature.COMPLETION to UiLayer.CARET_POPUP,
        LspFeature.HOVER to UiLayer.CARET_POPUP,
        LspFeature.SIGNATURE_HELP to UiLayer.CARET_POPUP,
        LspFeature.DOCUMENT_HIGHLIGHT to UiLayer.BACKGROUND_RANGE,
        LspFeature.INLAY_HINTS to UiLayer.INLINE_TEXT,
        LspFeature.CODE_LENS to UiLayer.BETWEEN_LINE_BLOCK,
        LspFeature.SEMANTIC_TOKENS to UiLayer.TOKEN_OVERLAY,
        LspFeature.DOCUMENT_LINK to UiLayer.UNDERLINE,
    )

    private val CODE_ACTION_KINDS = listOf(
        "", "quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite",
        "source", "source.organizeImports", "source.fixAll",
    )
    private val SEMANTIC_RETRY_METHODS = listOf(
        "textDocument/semanticTokens/full", "textDocument/semanticTokens/range", "textDocument/semanticTokens/full/delta",
    )
    private val DOC_FORMATS = listOf(MarkupKind.MARKDOWN.wire, MarkupKind.PLAINTEXT.wire)
    private val FOLDING_KINDS = listOf("comment", "imports", "region")
    private val COMPLETION_DEFAULTS = listOf("commitCharacters", "editRange", "insertTextFormat", "data")
    private val RESOURCE_OPS = listOf("create", "rename", "delete")
    private const val POSITION_ENCODING = "utf-16"
    private const val MARKDOWN_PARSER = "easyide"
    private const val MARKDOWN_VERSION = "1"
    private const val INSERT_TEXT_MODE_AS_IS = 1
    private const val PREPARE_DEFAULT_BEHAVIOR_IDENTIFIER = 1

    /** `SymbolTag.Deprecated` and `CompletionItemTag.Deprecated` share the value 1. */
    private const val TAG_DEPRECATED = 1

    /** The one position encoding offered; `initialize` results naming another are rejected. */
    const val OFFERED_POSITION_ENCODING: String = POSITION_ENCODING

    /** Features whose client side exists at [milestone] with [ui]. */
    fun advertisedFeatures(milestone: Milestone, ui: ClientUi): Set<LspFeature> {
        val base = if (milestone == Milestone.M4) M2_FEATURES + M4_FEATURES else M2_FEATURES
        return base.filterTo(mutableSetOf()) { f ->
            val layer = REQUIRED_LAYER[f]
            f !in ui.withheld &&
                (layer == null || layer in ui.layers) &&
                (f != LspFeature.SEMANTIC_TOKENS || ui.semanticTokenTypes.isNotEmpty())
        }
    }

    fun build(milestone: Milestone, ui: ClientUi): JsonObject {
        val features = advertisedFeatures(milestone, ui)
        return buildJsonObject {
            put("general", general())
            // No `showDocument`: window/showDocument is not handled in v1.
            put("window", obj {
                put("workDoneProgress", JsonPrimitive(true))
                put("showMessage", obj {
                    put("messageActionItem", obj { put("additionalPropertiesSupport", JsonPrimitive(false)) })
                })
            })
            put("workspace", workspace(features))
            put("textDocument", textDocument(features, ui))
        }
    }

    private fun general() = obj {
        put("positionEncodings", jsonStrings(listOf(POSITION_ENCODING)))
        put("staleRequestSupport", obj {
            put("cancel", JsonPrimitive(true))
            put("retryOnContentModified", jsonStrings(SEMANTIC_RETRY_METHODS))
        })
        put("markdown", obj {
            put("parser", JsonPrimitive(MARKDOWN_PARSER))
            put("version", JsonPrimitive(MARKDOWN_VERSION))
        })
    }

    private fun workspace(features: Set<LspFeature>) = obj {
        put("applyEdit", JsonPrimitive(true))
        put("workspaceEdit", obj {
            put("documentChanges", JsonPrimitive(true))
            put("resourceOperations", jsonStrings(RESOURCE_OPS))
            put("failureHandling", JsonPrimitive("abort"))
            put("normalizesLineEndings", JsonPrimitive(false))
        })
        put("didChangeConfiguration", dynamic(true))
        put("didChangeWatchedFiles", obj {
            put("dynamicRegistration", JsonPrimitive(true))
            put("relativePatternSupport", JsonPrimitive(true))
        })
        put("configuration", JsonPrimitive(true))
        put("workspaceFolders", JsonPrimitive(true))
        put("executeCommand", dynamic(false))
        if (LspFeature.WORKSPACE_SYMBOL in features) {
            put("symbol", obj { put("symbolKind", valueSet(SymbolKind.entries.map { it.wire })) })
        }
        if (LspFeature.SEMANTIC_TOKENS in features) put("semanticTokens", refresh())
        if (LspFeature.INLAY_HINTS in features) put("inlayHint", refresh())
        if (LspFeature.CODE_LENS in features) put("codeLens", refresh())
        // Pull diagnostics exist from M2; the refresh request re-pulls open documents.
        put("diagnostics", refresh())
    }

    private fun textDocument(features: Set<LspFeature>, ui: ClientUi) = obj {
        put("synchronization", obj {
            put("dynamicRegistration", JsonPrimitive(false))
            put("willSave", JsonPrimitive(false))
            put("willSaveWaitUntil", JsonPrimitive(true))
            put("didSave", JsonPrimitive(true))
        })
        put("publishDiagnostics", obj {
            put("relatedInformation", JsonPrimitive(true))
            put("versionSupport", JsonPrimitive(true))
            put("tagSupport", valueSet(DiagnosticTag.entries.map { it.wire }))
            put("codeDescriptionSupport", JsonPrimitive(true))
            put("dataSupport", JsonPrimitive(true))
        })
        put("diagnostic", obj { put("relatedDocumentSupport", JsonPrimitive(false)) })
        if (LspFeature.COMPLETION in features) put("completion", completion())
        if (LspFeature.HOVER in features) put("hover", obj { put("contentFormat", jsonStrings(DOC_FORMATS)) })
        if (LspFeature.SIGNATURE_HELP in features) put("signatureHelp", signatureHelp())
        for ((feature, key) in LINK_FEATURES) {
            if (feature in features) put(key, obj { put("linkSupport", JsonPrimitive(true)) })
        }
        if (LspFeature.REFERENCES in features) put("references", obj {})
        if (LspFeature.DOCUMENT_HIGHLIGHT in features) put("documentHighlight", obj {})
        if (LspFeature.DOCUMENT_SYMBOL in features) put("documentSymbol", obj {
            put("hierarchicalDocumentSymbolSupport", JsonPrimitive(true))
            put("symbolKind", valueSet(SymbolKind.entries.map { it.wire }))
            put("tagSupport", valueSet(listOf(TAG_DEPRECATED)))
            put("labelSupport", JsonPrimitive(false))
        })
        if (LspFeature.RENAME in features) put("rename", obj {
            put("prepareSupport", JsonPrimitive(true))
            put("prepareSupportDefaultBehavior", JsonPrimitive(PREPARE_DEFAULT_BEHAVIOR_IDENTIFIER))
            put("honorsChangeAnnotations", JsonPrimitive(false))
        })
        if (LspFeature.CODE_ACTION in features) put("codeAction", codeAction())
        if (LspFeature.FORMATTING in features) put("formatting", obj {})
        if (LspFeature.RANGE_FORMATTING in features) put("rangeFormatting", obj { put("rangesSupport", JsonPrimitive(false)) })
        if (LspFeature.ON_TYPE_FORMATTING in features) put("onTypeFormatting", obj {})
        if (LspFeature.INLAY_HINTS in features) put("inlayHint", obj {
            put("resolveSupport", obj { put("properties", jsonStrings(listOf("tooltip", "label.tooltip", "label.location", "textEdits"))) })
        })
        if (LspFeature.SEMANTIC_TOKENS in features) put("semanticTokens", semanticTokens(ui))
        if (LspFeature.FOLDING_RANGE in features) put("foldingRange", obj {
            put("rangeLimit", JsonPrimitive(LspPolicy.FOLDING_RANGE_LIMIT))
            put("lineFoldingOnly", JsonPrimitive(true))
            put("foldingRangeKind", obj { put("valueSet", jsonStrings(FOLDING_KINDS)) })
        })
        if (LspFeature.SELECTION_RANGE in features) put("selectionRange", obj {})
        if (LspFeature.DOCUMENT_LINK in features) put("documentLink", obj { put("tooltipSupport", JsonPrimitive(true)) })
        if (LspFeature.CODE_LENS in features) put("codeLens", obj {})
    }

    private val LINK_FEATURES = listOf(
        LspFeature.DEFINITION to "definition",
        LspFeature.DECLARATION to "declaration",
        LspFeature.TYPE_DEFINITION to "typeDefinition",
        LspFeature.IMPLEMENTATION to "implementation",
    )

    private fun completion() = obj {
        put("contextSupport", JsonPrimitive(true))
        put("insertTextMode", JsonPrimitive(INSERT_TEXT_MODE_AS_IS))
        put("completionItem", obj {
            put("snippetSupport", JsonPrimitive(true))
            put("commitCharactersSupport", JsonPrimitive(true))
            put("documentationFormat", jsonStrings(DOC_FORMATS))
            put("deprecatedSupport", JsonPrimitive(true))
            put("preselectSupport", JsonPrimitive(true))
            put("tagSupport", valueSet(listOf(TAG_DEPRECATED)))
            put("insertReplaceSupport", JsonPrimitive(true))
            put("resolveSupport", obj { put("properties", jsonStrings(listOf("documentation", "detail", "additionalTextEdits"))) })
            put("labelDetailsSupport", JsonPrimitive(true))
        })
        put("completionItemKind", valueSet(CompletionItemKind.entries.map { it.wire }))
        put("completionList", obj { put("itemDefaults", jsonStrings(COMPLETION_DEFAULTS)) })
    }

    private fun signatureHelp() = obj {
        put("contextSupport", JsonPrimitive(true))
        put("signatureInformation", obj {
            put("documentationFormat", jsonStrings(DOC_FORMATS))
            put("parameterInformation", obj { put("labelOffsetSupport", JsonPrimitive(true)) })
            put("activeParameterSupport", JsonPrimitive(true))
        })
    }

    private fun codeAction() = obj {
        put("codeActionLiteralSupport", obj {
            put("codeActionKind", obj { put("valueSet", jsonStrings(CODE_ACTION_KINDS)) })
        })
        put("isPreferredSupport", JsonPrimitive(true))
        put("disabledSupport", JsonPrimitive(true))
        put("dataSupport", JsonPrimitive(true))
        put("resolveSupport", obj { put("properties", jsonStrings(listOf("edit"))) })
    }

    private fun semanticTokens(ui: ClientUi) = obj {
        put("requests", obj {
            put("range", JsonPrimitive(true))
            put("full", obj { put("delta", JsonPrimitive(true)) })
        })
        put("tokenTypes", jsonStrings(ui.semanticTokenTypes))
        put("tokenModifiers", jsonStrings(ui.semanticTokenModifiers))
        put("formats", jsonStrings(listOf("relative")))
        put("overlappingTokenSupport", JsonPrimitive(false))
        put("multilineTokenSupport", JsonPrimitive(false))
        put("serverCancelSupport", JsonPrimitive(true))
        put("augmentsSyntaxTokens", JsonPrimitive(true))
    }

    private fun refresh() = obj { put("refreshSupport", JsonPrimitive(true)) }

    private fun dynamic(value: Boolean) = obj { put("dynamicRegistration", JsonPrimitive(value)) }

    private fun valueSet(values: List<Int>) = obj { put("valueSet", jsonInts(values)) }

    private inline fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
}
