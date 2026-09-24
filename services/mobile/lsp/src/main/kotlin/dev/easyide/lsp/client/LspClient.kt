package dev.easyide.lsp.client

import dev.easyide.lsp.client.FeatureRouting.dedupe
import dev.easyide.lsp.client.FeatureRouting.firstNonEmpty
import dev.easyide.lsp.client.FeatureRouting.merge
import dev.easyide.lsp.client.FeatureRouting.owner
import dev.easyide.lsp.client.FeatureRouting.quietly
import dev.easyide.lsp.client.FeatureRouting.single
import dev.easyide.lsp.diagnostics.DiagnosticSet
import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.protocol.CodeAction
import dev.easyide.lsp.protocol.CodeActionTriggerKind
import dev.easyide.lsp.protocol.CodeLens
import dev.easyide.lsp.protocol.Command
import dev.easyide.lsp.protocol.CompletionItem
import dev.easyide.lsp.protocol.CompletionList
import dev.easyide.lsp.protocol.CompletionTriggerKind
import dev.easyide.lsp.protocol.Diagnostic
import dev.easyide.lsp.protocol.DocumentHighlight
import dev.easyide.lsp.protocol.DocumentLink
import dev.easyide.lsp.protocol.FoldingRange
import dev.easyide.lsp.protocol.FormattingOptions
import dev.easyide.lsp.protocol.Hover
import dev.easyide.lsp.protocol.InlayHint
import dev.easyide.lsp.protocol.Location
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.LspMethod
import dev.easyide.lsp.protocol.LspMethods
import dev.easyide.lsp.protocol.LspParams
import dev.easyide.lsp.protocol.NavTarget
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.PrepareRename
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SelectionRangeChain
import dev.easyide.lsp.protocol.ServerCapabilities
import dev.easyide.lsp.protocol.SignatureHelp
import dev.easyide.lsp.protocol.SignatureHelpContext
import dev.easyide.lsp.protocol.SymbolNode
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.protocol.WorkspaceSymbolItem
import dev.easyide.lsp.protocol.documentParams
import dev.easyide.lsp.protocol.positionParams
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.session.RefreshKind
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.workspace.ApplyResult
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * The facade the app uses (hld.md `LspClient`): document lifecycle, typed feature requests
 * with the routing policy of each feature (lsp-features.md 1.1), edits, status.
 *
 * Every call is safe off the main thread and returns already-decoded, immutable values;
 * presenters only swap them into UI state. A null or empty answer means "nothing to show" -
 * timeouts and cancellations are silent (arch.md 7.4). Single-owner calls (rename,
 * formatting) throw [dev.easyide.lsp.session.LspRequestException] so the user sees why.
 */
class LspClient(private val manager: LanguageServerManager) {

    private val semanticTokens = SemanticTokensCache()

    val statuses: StateFlow<Map<ServerKey, ServerStatus>> get() = manager.statuses

    /**
     * `workspace/{semanticTokens,inlayHint,codeLens}/refresh` from any server of the project,
     * for presenters to re-run that feature for visible documents (diagnostics refresh is
     * handled inside the session).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshes(environmentId: String, projectId: String): Flow<RefreshKind> = manager.statuses
        .map { m -> m.keys.filterTo(HashSet()) { it.environmentId == environmentId && it.projectId == projectId } }
        .distinctUntilChanged()
        .flatMapLatest { keys -> keys.mapNotNull(manager::session).map { it.refreshes }.merge() }

    fun diagnostics(environmentId: String, projectId: String): StateFlow<Map<String, Map<ServerKey, DiagnosticSet>>> =
        manager.diagnostics(environmentId, projectId).byUri

    // ---- documents ------------------------------------------------------------------------

    /** Guest URI of a host file, or null when the guest cannot see it. */
    fun uriFor(environmentId: String, projectId: String, host: File): String? =
        manager.pathMapper(environmentId, projectId).toGuestUri(host)

    fun openDocument(doc: DocContext, text: String) =
        manager.documentStore(doc.environmentId, doc.projectId).open(doc.uri, doc.languageId, text)

    fun changeDocument(environmentId: String, projectId: String, uri: String, text: String) =
        manager.documentStore(environmentId, projectId).update(uri, text)

    fun savedDocument(environmentId: String, projectId: String, uri: String, text: String) =
        manager.documentStore(environmentId, projectId).saved(uri, text)

    fun closeDocument(environmentId: String, projectId: String, uri: String) {
        manager.documentStore(environmentId, projectId).close(uri)
        semanticTokens.forget(FileUri.canonical(uri))
    }

    fun releaseProject(environmentId: String, projectId: String) = manager.releaseProject(environmentId, projectId)

    // ---- merge ----------------------------------------------------------------------------

    suspend fun completion(doc: DocContext, at: Position, trigger: CompletionTriggerKind, triggerCharacter: String?): List<FromServer<CompletionList>> {
        val sessions = sessions(doc).filter { it.supports(LspFeature.COMPLETION) }
        return coroutineScope {
            sessions.map { s ->
                // A session only gets trigger kind 2 for its own trigger characters (lsp-features.md 4.2).
                val own = triggerCharacter != null && s.capabilities.value?.completionTriggerCharacters?.contains(triggerCharacter) == true
                val kind = if (trigger == CompletionTriggerKind.TRIGGER_CHARACTER && !own) CompletionTriggerKind.INVOKED else trigger
                async { quietly(s, LspMethods.COMPLETION, LspParams.completion(doc.uri, at, kind, triggerCharacter), doc.uri) }
            }.awaitAll().filterNotNull()
        }
    }

    /** Resolves on the item's own server; returns the item unchanged if that server cannot resolve. */
    suspend fun resolveCompletion(environmentId: String, projectId: String, item: FromServer<CompletionItem>): CompletionItem {
        val s = session(environmentId, projectId, item.server) ?: return item.value
        if (s.capabilities.value?.completionResolve != true) return item.value
        return quietly(s, LspMethods.COMPLETION_RESOLVE, item.value.raw, null)?.value ?: item.value
    }

    suspend fun hover(doc: DocContext, at: Position): List<FromServer<Hover>> =
        merge(sessions(doc), LspFeature.HOVER, LspMethods.HOVER, positionParams(doc.uri, at), doc.uri)
            .mapNotNull { r -> r.value?.let { FromServer(r.server, it, r.version) } }
            .distinctBy { it.value.contents }

    suspend fun references(doc: DocContext, at: Position, includeDeclaration: Boolean): List<FromServer<Location>> =
        dedupe(merge(sessions(doc), LspFeature.REFERENCES, LspMethods.REFERENCES, LspParams.references(doc.uri, at, includeDeclaration), doc.uri)) {
            FileUri.canonical(it.uri) to it.range
        }

    suspend fun documentHighlights(doc: DocContext, at: Position): List<FromServer<DocumentHighlight>> =
        dedupe(merge(sessions(doc), LspFeature.DOCUMENT_HIGHLIGHT, LspMethods.DOCUMENT_HIGHLIGHT, positionParams(doc.uri, at), doc.uri)) { it.range }

    suspend fun documentSymbols(doc: DocContext): List<FromServer<SymbolNode>> =
        dedupe(merge(sessions(doc), LspFeature.DOCUMENT_SYMBOL, LspMethods.DOCUMENT_SYMBOL, documentParams(doc.uri), doc.uri)) { it.name to it.selectionRange }

    /** `workspace/symbol` across the servers of [languageId] (the palette `#` prefix). */
    suspend fun workspaceSymbols(environmentId: String, projectId: String, languageId: String, query: String): List<FromServer<WorkspaceSymbolItem>> =
        dedupe(merge(manager.sessionsFor(environmentId, projectId, languageId), LspFeature.WORKSPACE_SYMBOL, LspMethods.WORKSPACE_SYMBOL, LspParams.workspaceSymbol(query), null)) {
            it.name to it.location
        }

    suspend fun codeActions(
        doc: DocContext,
        range: Range,
        diagnostics: List<Diagnostic>,
        only: List<String>?,
        trigger: CodeActionTriggerKind,
    ): List<FromServer<CodeAction>> =
        dedupe(merge(sessions(doc), LspFeature.CODE_ACTION, LspMethods.CODE_ACTION, LspParams.codeAction(doc.uri, range, diagnostics, only, trigger), doc.uri)) {
            it.title to it.kind
        }

    /** Fills `edit` of an action that has neither edit nor command, on its own server. */
    suspend fun resolveCodeAction(environmentId: String, projectId: String, action: FromServer<CodeAction>): CodeAction {
        if (!action.value.needsResolve) return action.value
        val s = session(environmentId, projectId, action.server) ?: return action.value
        if (s.capabilities.value?.codeActionResolve != true) return action.value
        return quietly(s, LspMethods.CODE_ACTION_RESOLVE, action.value.raw, null)?.value ?: action.value
    }

    suspend fun inlayHints(doc: DocContext, range: Range): List<FromServer<InlayHint>> =
        dedupe(merge(sessions(doc), LspFeature.INLAY_HINTS, LspMethods.INLAY_HINT, LspParams.range(doc.uri, range), doc.uri)) { it.position to it.text }

    suspend fun resolveInlayHint(environmentId: String, projectId: String, hint: FromServer<InlayHint>): InlayHint =
        resolve(environmentId, projectId, hint, LspMethods.INLAY_HINT_RESOLVE, hint.value.raw) { it.inlayHintResolve }

    suspend fun foldingRanges(doc: DocContext): List<FromServer<FoldingRange>> =
        dedupe(merge(sessions(doc), LspFeature.FOLDING_RANGE, LspMethods.FOLDING_RANGE, documentParams(doc.uri), doc.uri)) { it.startLine to it.endLine }

    suspend fun codeLenses(doc: DocContext): List<FromServer<CodeLens>> =
        dedupe(merge(sessions(doc), LspFeature.CODE_LENS, LspMethods.CODE_LENS, documentParams(doc.uri), doc.uri)) { it.range to it.command?.title }

    suspend fun resolveCodeLens(environmentId: String, projectId: String, lens: FromServer<CodeLens>): CodeLens =
        if (lens.value.command != null) lens.value else resolve(environmentId, projectId, lens, LspMethods.CODE_LENS_RESOLVE, lens.value.raw) { it.codeLensResolve }

    suspend fun documentLinks(doc: DocContext): List<FromServer<DocumentLink>> =
        dedupe(merge(sessions(doc), LspFeature.DOCUMENT_LINK, LspMethods.DOCUMENT_LINK, documentParams(doc.uri), doc.uri)) { it.range to it.target }

    suspend fun resolveDocumentLink(environmentId: String, projectId: String, link: FromServer<DocumentLink>): DocumentLink =
        if (link.value.target != null) link.value else resolve(environmentId, projectId, link, LspMethods.DOCUMENT_LINK_RESOLVE, link.value.raw) { it.documentLinkResolve }

    // ---- first non-empty ------------------------------------------------------------------

    suspend fun definition(doc: DocContext, at: Position) = navigation(doc, at, LspFeature.DEFINITION, LspMethods.DEFINITION)
    suspend fun declaration(doc: DocContext, at: Position) = navigation(doc, at, LspFeature.DECLARATION, LspMethods.DECLARATION)
    suspend fun typeDefinition(doc: DocContext, at: Position) = navigation(doc, at, LspFeature.TYPE_DEFINITION, LspMethods.TYPE_DEFINITION)
    suspend fun implementation(doc: DocContext, at: Position) = navigation(doc, at, LspFeature.IMPLEMENTATION, LspMethods.IMPLEMENTATION)

    suspend fun signatureHelp(doc: DocContext, at: Position, context: SignatureHelpContext): FromServer<SignatureHelp>? =
        firstNonEmpty(sessions(doc), LspFeature.SIGNATURE_HELP, LspMethods.SIGNATURE_HELP, LspParams.signatureHelp(doc.uri, at, context), doc.uri) { it == null }
            ?.let { r -> r.value?.let { FromServer(r.server, it, r.version) } }

    suspend fun selectionRanges(doc: DocContext, positions: List<Position>): FromServer<List<SelectionRangeChain>>? =
        firstNonEmpty(sessions(doc), LspFeature.SELECTION_RANGE, LspMethods.SELECTION_RANGE, LspParams.selectionRange(doc.uri, positions), doc.uri) {
            it.all { chain -> chain.ranges.isEmpty() }
        }

    // ---- single owner ---------------------------------------------------------------------

    /** Null means "cannot rename here" (no owner, null answer). */
    suspend fun prepareRename(doc: DocContext, at: Position): FromServer<PrepareRename>? {
        val s = owner(sessions(doc), LspFeature.RENAME, null) ?: return null
        if (s.capabilities.value?.prepareRename != true) return FromServer(s.key, PrepareRename.DefaultBehavior, s.syncedVersion(doc))
        val r = single(s, LspMethods.PREPARE_RENAME, positionParams(doc.uri, at), doc.uri) ?: return null
        return r.value?.let { FromServer(r.server, it, r.version) }
    }

    suspend fun rename(doc: DocContext, at: Position, newName: String): FromServer<WorkspaceEdit>? {
        val s = owner(sessions(doc), LspFeature.RENAME, null) ?: return null
        val r = single(s, LspMethods.RENAME, LspParams.rename(doc.uri, at, newName), doc.uri) ?: return null
        return r.value?.let { FromServer(r.server, it, r.version) }
    }

    suspend fun formatting(doc: DocContext, options: FormattingOptions, defaultFormatter: String?): FromServer<List<TextEdit>>? =
        owner(sessions(doc), LspFeature.FORMATTING, defaultFormatter)?.let { single(it, LspMethods.FORMATTING, LspParams.formatting(doc.uri, options), doc.uri) }

    suspend fun rangeFormatting(doc: DocContext, range: Range, options: FormattingOptions, defaultFormatter: String?): FromServer<List<TextEdit>>? =
        owner(sessions(doc), LspFeature.RANGE_FORMATTING, defaultFormatter)?.let {
            single(it, LspMethods.RANGE_FORMATTING, LspParams.rangeFormatting(doc.uri, range, options), doc.uri)
        }

    /** Only the owner whose trigger characters include [ch] is asked. */
    suspend fun onTypeFormatting(doc: DocContext, at: Position, ch: String, options: FormattingOptions, defaultFormatter: String?): FromServer<List<TextEdit>>? {
        val candidates = sessions(doc).filter { ch in it.capabilities.value?.onTypeFormattingTriggers.orEmpty() }
        val s = owner(candidates, LspFeature.ON_TYPE_FORMATTING, defaultFormatter) ?: return null
        return single(s, LspMethods.ON_TYPE_FORMATTING, LspParams.onTypeFormatting(doc.uri, at, ch, options), doc.uri)
    }

    /**
     * `willSaveWaitUntil` from the formatting owner when it supports it (lsp-client.md 5.3);
     * null otherwise, and the caller runs the formatting pipeline instead. Never throws: a
     * failing server must not block a save.
     */
    suspend fun willSaveWaitUntil(doc: DocContext, defaultFormatter: String?): FromServer<List<TextEdit>>? {
        val candidates = sessions(doc).filter { it.capabilities.value?.sync?.willSaveWaitUntil == true }
        val s = owner(candidates, LspFeature.FORMATTING, defaultFormatter) ?: candidates.firstOrNull() ?: return null
        return quietly(s, LspMethods.WILL_SAVE_WAIT_UNTIL, LspParams.willSaveWaitUntil(doc.uri), doc.uri)
    }

    /** Full (or delta-updated) semantic tokens from the owning server, decoded with its legend. */
    suspend fun semanticTokens(doc: DocContext): FromServer<SemanticTokensResult>? {
        val s = owner(sessions(doc), LspFeature.SEMANTIC_TOKENS, null) ?: return null
        return semanticTokens.full(s, doc.uri)
    }

    /** Tokens for [range] only (first paint of a large document). */
    suspend fun semanticTokensRange(doc: DocContext, range: Range): FromServer<SemanticTokensResult>? {
        val s = owner(sessions(doc), LspFeature.SEMANTIC_TOKENS, null) ?: return null
        return semanticTokens.range(s, doc.uri, range)
    }

    // ---- commands and edits -----------------------------------------------------------------

    /** `workspace/executeCommand` on the server that produced [command]. */
    suspend fun executeCommand(environmentId: String, projectId: String, server: ServerKey, command: Command): JsonElement? {
        val s = session(environmentId, projectId, server) ?: return null
        return quietly(s, LspMethods.EXECUTE_COMMAND, LspParams.executeCommand(command), null)?.value
    }

    suspend fun applyEdit(environmentId: String, projectId: String, edit: WorkspaceEdit, label: String): ApplyResult =
        manager.editApplier(environmentId, projectId).apply(edit, label)

    // ---- helpers ------------------------------------------------------------------------------

    private fun sessions(doc: DocContext): List<LspSession> = manager.sessionsFor(doc.environmentId, doc.projectId, doc.languageId)

    private fun session(environmentId: String, projectId: String, key: ServerKey): LspSession? =
        manager.session(key)?.takeIf { key.environmentId == environmentId && key.projectId == projectId }

    private suspend fun navigation(doc: DocContext, at: Position, feature: LspFeature, method: LspMethod<List<NavTarget>>): FromServer<List<NavTarget>>? =
        firstNonEmpty(sessions(doc), feature, method, positionParams(doc.uri, at), doc.uri) { it.isEmpty() }

    private suspend fun <T> resolve(
        environmentId: String,
        projectId: String,
        item: FromServer<T>,
        method: LspMethod<T?>,
        raw: JsonElement,
        canResolve: (ServerCapabilities) -> Boolean,
    ): T {
        val s = session(environmentId, projectId, item.server) ?: return item.value
        val caps = s.capabilities.value ?: return item.value
        if (!canResolve(caps)) return item.value
        return quietly(s, method, raw, null)?.value ?: item.value
    }

    private fun LspSession.syncedVersion(doc: DocContext): Int? =
        manager.documentStore(doc.environmentId, doc.projectId).snapshot(doc.uri)?.version?.takeIf { FileUri.canonical(doc.uri) in openUris }
}
