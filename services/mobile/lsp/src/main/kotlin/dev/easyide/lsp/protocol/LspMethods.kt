package dev.easyide.lsp.protocol

import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.putOpt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A typed request: wire [name], the [feature] that gates it (null = always allowed when the
 * session is ready) and the total [decode] of its result.
 */
class LspMethod<R>(val name: String, val feature: LspFeature?, val decode: (JsonElement) -> R)

/** Every request the client sends, decoded into the protocol types (lsp-features.md sec 4). */
object LspMethods {
    val COMPLETION = LspMethod("textDocument/completion", LspFeature.COMPLETION, CompletionList::fromJson)
    val COMPLETION_RESOLVE = LspMethod("completionItem/resolve", LspFeature.COMPLETION, CompletionItem::fromJson)
    val HOVER = LspMethod("textDocument/hover", LspFeature.HOVER, Hover::fromJson)
    val SIGNATURE_HELP = LspMethod("textDocument/signatureHelp", LspFeature.SIGNATURE_HELP, SignatureHelp::fromJson)
    val DEFINITION = LspMethod("textDocument/definition", LspFeature.DEFINITION, NavTarget::listFromJson)
    val DECLARATION = LspMethod("textDocument/declaration", LspFeature.DECLARATION, NavTarget::listFromJson)
    val TYPE_DEFINITION = LspMethod("textDocument/typeDefinition", LspFeature.TYPE_DEFINITION, NavTarget::listFromJson)
    val IMPLEMENTATION = LspMethod("textDocument/implementation", LspFeature.IMPLEMENTATION, NavTarget::listFromJson)
    val REFERENCES = LspMethod("textDocument/references", LspFeature.REFERENCES) { e -> e.mapItems(Location::fromJson) }
    val DOCUMENT_HIGHLIGHT = LspMethod("textDocument/documentHighlight", LspFeature.DOCUMENT_HIGHLIGHT, DocumentHighlight::listFromJson)
    val DOCUMENT_SYMBOL = LspMethod("textDocument/documentSymbol", LspFeature.DOCUMENT_SYMBOL, SymbolNode::listFromJson)
    val WORKSPACE_SYMBOL = LspMethod("workspace/symbol", LspFeature.WORKSPACE_SYMBOL, WorkspaceSymbolItem::listFromJson)
    val PREPARE_RENAME = LspMethod("textDocument/prepareRename", LspFeature.RENAME, PrepareRename::fromJson)
    val RENAME = LspMethod("textDocument/rename", LspFeature.RENAME, WorkspaceEdit::fromJson)
    val CODE_ACTION = LspMethod("textDocument/codeAction", LspFeature.CODE_ACTION, CodeAction::listFromJson)
    val CODE_ACTION_RESOLVE = LspMethod("codeAction/resolve", LspFeature.CODE_ACTION, CodeAction::fromJson)
    val FORMATTING = LspMethod("textDocument/formatting", LspFeature.FORMATTING, TextEdit::listFromJson)
    val RANGE_FORMATTING = LspMethod("textDocument/rangeFormatting", LspFeature.RANGE_FORMATTING, TextEdit::listFromJson)
    val ON_TYPE_FORMATTING = LspMethod("textDocument/onTypeFormatting", LspFeature.ON_TYPE_FORMATTING, TextEdit::listFromJson)

    /** Gated by `textDocumentSync.willSaveWaitUntil`, not a feature id. */
    val WILL_SAVE_WAIT_UNTIL = LspMethod("textDocument/willSaveWaitUntil", null, TextEdit::listFromJson)
    val INLAY_HINT = LspMethod("textDocument/inlayHint", LspFeature.INLAY_HINTS, InlayHint::listFromJson)
    val INLAY_HINT_RESOLVE = LspMethod("inlayHint/resolve", LspFeature.INLAY_HINTS, InlayHint::fromJson)
    val SEMANTIC_TOKENS_FULL = LspMethod("textDocument/semanticTokens/full", LspFeature.SEMANTIC_TOKENS, SemanticTokensData::fromJson)
    val SEMANTIC_TOKENS_DELTA = LspMethod("textDocument/semanticTokens/full/delta", LspFeature.SEMANTIC_TOKENS, SemanticTokensDeltaResult::fromJson)
    val SEMANTIC_TOKENS_RANGE = LspMethod("textDocument/semanticTokens/range", LspFeature.SEMANTIC_TOKENS, SemanticTokensData::fromJson)
    val FOLDING_RANGE = LspMethod("textDocument/foldingRange", LspFeature.FOLDING_RANGE, FoldingRange::listFromJson)
    val SELECTION_RANGE = LspMethod("textDocument/selectionRange", LspFeature.SELECTION_RANGE, SelectionRangeChain::listFromJson)
    val CODE_LENS = LspMethod("textDocument/codeLens", LspFeature.CODE_LENS, CodeLens::listFromJson)
    val CODE_LENS_RESOLVE = LspMethod("codeLens/resolve", LspFeature.CODE_LENS, CodeLens::fromJson)
    val DOCUMENT_LINK = LspMethod("textDocument/documentLink", LspFeature.DOCUMENT_LINK, DocumentLink::listFromJson)
    val DOCUMENT_LINK_RESOLVE = LspMethod("documentLink/resolve", LspFeature.DOCUMENT_LINK, DocumentLink::fromJson)
    val PULL_DIAGNOSTICS = LspMethod("textDocument/diagnostic", LspFeature.DIAGNOSTICS, DocumentDiagnosticReport::fromJson)

    /** Result is server-defined; returned raw. */
    val EXECUTE_COMMAND = LspMethod("workspace/executeCommand", null) { it }

    /** Every method above, for callers that start from a wire name (extension `lspRequest`). */
    val ALL: List<LspMethod<*>> = listOf(
        COMPLETION, COMPLETION_RESOLVE, HOVER, SIGNATURE_HELP, DEFINITION, DECLARATION, TYPE_DEFINITION, IMPLEMENTATION,
        REFERENCES, DOCUMENT_HIGHLIGHT, DOCUMENT_SYMBOL, WORKSPACE_SYMBOL, PREPARE_RENAME, RENAME, CODE_ACTION,
        CODE_ACTION_RESOLVE, FORMATTING, RANGE_FORMATTING, ON_TYPE_FORMATTING, WILL_SAVE_WAIT_UNTIL, INLAY_HINT,
        INLAY_HINT_RESOLVE, SEMANTIC_TOKENS_FULL, SEMANTIC_TOKENS_DELTA, SEMANTIC_TOKENS_RANGE, FOLDING_RANGE,
        SELECTION_RANGE, CODE_LENS, CODE_LENS_RESOLVE, DOCUMENT_LINK, DOCUMENT_LINK_RESOLVE, PULL_DIAGNOSTICS, EXECUTE_COMMAND,
    )
}

/** `FormattingOptions` from the editor keys of the language (lsp-features.md 4.11). */
data class FormattingOptions(
    val tabSize: Int,
    val insertSpaces: Boolean,
    val trimTrailingWhitespace: Boolean? = null,
    val insertFinalNewline: Boolean? = null,
    val trimFinalNewlines: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tabSize", JsonPrimitive(tabSize))
        put("insertSpaces", JsonPrimitive(insertSpaces))
        putOpt("trimTrailingWhitespace", trimTrailingWhitespace)
        putOpt("insertFinalNewline", insertFinalNewline)
        putOpt("trimFinalNewlines", trimFinalNewlines)
    }
}

/** Signature-help context (lsp-features.md 4.4); [active] is the help currently shown, if any. */
data class SignatureHelpContext(
    val triggerKind: Int,
    val triggerCharacter: String?,
    val isRetrigger: Boolean,
    val active: JsonObject?,
)

/** Params builders; each returns exactly the shape the spec names, optional fields absent. */
object LspParams {
    fun completion(uri: String, position: Position, triggerKind: CompletionTriggerKind, triggerCharacter: String?): JsonObject =
        buildJsonObject {
            put("textDocument", TextDocumentIdentifier(uri).toJson())
            put("position", position.toJson())
            put("context", buildJsonObject {
                put("triggerKind", JsonPrimitive(triggerKind.wire))
                putOpt("triggerCharacter", if (triggerKind == CompletionTriggerKind.TRIGGER_CHARACTER) triggerCharacter else null)
            })
        }

    fun signatureHelp(uri: String, position: Position, context: SignatureHelpContext): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("position", position.toJson())
        put("context", buildJsonObject {
            put("triggerKind", JsonPrimitive(context.triggerKind))
            putOpt("triggerCharacter", context.triggerCharacter)
            put("isRetrigger", JsonPrimitive(context.isRetrigger))
            putOpt("activeSignatureHelp", context.active)
        })
    }

    fun references(uri: String, position: Position, includeDeclaration: Boolean): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("position", position.toJson())
        put("context", buildJsonObject { put("includeDeclaration", JsonPrimitive(includeDeclaration)) })
    }

    fun rename(uri: String, position: Position, newName: String): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("position", position.toJson())
        put("newName", JsonPrimitive(newName))
    }

    /** [diagnostics] are echoed verbatim (`Diagnostic.raw`) so the server can rebuild fixes from `data`. */
    fun codeAction(uri: String, range: Range, diagnostics: List<Diagnostic>, only: List<String>?, triggerKind: CodeActionTriggerKind): JsonObject =
        buildJsonObject {
            put("textDocument", TextDocumentIdentifier(uri).toJson())
            put("range", range.toJson())
            put("context", buildJsonObject {
                put("diagnostics", JsonArray(diagnostics.map { it.raw }))
                putOpt("only", only?.let { kinds -> JsonArray(kinds.map { JsonPrimitive(it) }) })
                put("triggerKind", JsonPrimitive(triggerKind.wire))
            })
        }

    fun formatting(uri: String, options: FormattingOptions): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("options", options.toJson())
    }

    fun rangeFormatting(uri: String, range: Range, options: FormattingOptions): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("range", range.toJson())
        put("options", options.toJson())
    }

    fun onTypeFormatting(uri: String, position: Position, ch: String, options: FormattingOptions): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("position", position.toJson())
        put("ch", JsonPrimitive(ch))
        put("options", options.toJson())
    }

    /** `TextDocumentSaveReason.Manual` = 1; saves are always user-initiated in the editor. */
    fun willSaveWaitUntil(uri: String): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("reason", JsonPrimitive(SAVE_REASON_MANUAL))
    }

    fun range(uri: String, range: Range): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("range", range.toJson())
    }

    fun semanticTokensDelta(uri: String, previousResultId: String): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("previousResultId", JsonPrimitive(previousResultId))
    }

    fun selectionRange(uri: String, positions: List<Position>): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        put("positions", JsonArray(positions.map { it.toJson() }))
    }

    fun workspaceSymbol(query: String): JsonObject = buildJsonObject { put("query", JsonPrimitive(query)) }

    fun pullDiagnostics(uri: String, identifier: String?, previousResultId: String?): JsonObject = buildJsonObject {
        put("textDocument", TextDocumentIdentifier(uri).toJson())
        putOpt("identifier", identifier)
        putOpt("previousResultId", previousResultId)
    }

    fun executeCommand(command: Command): JsonObject = buildJsonObject {
        put("command", JsonPrimitive(command.command))
        putOpt("arguments", command.arguments)
    }

    private const val SAVE_REASON_MANUAL = 1
}
