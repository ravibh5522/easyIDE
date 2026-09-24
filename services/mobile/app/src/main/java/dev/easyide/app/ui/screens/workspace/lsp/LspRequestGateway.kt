package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.R
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.LspMethod
import dev.easyide.lsp.protocol.LspMethods
import dev.easyide.lsp.protocol.NavTarget
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.protocol.positionParams
import dev.easyide.lsp.session.LspRequestException
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which requests an extension may send and how its `then` reads the answer (lsp-features.md
 * 4.18). Pure, so the rules are unit tests.
 */
object LspRequestPolicy {
    private const val TEXT_DOCUMENT_PREFIX = "textDocument/"

    /** Workspace methods besides `textDocument/`*; lifecycle (`initialize`, `shutdown`, `$/`...) never passes. */
    private val WORKSPACE_METHODS = setOf(LspMethods.WORKSPACE_SYMBOL.name, LspMethods.EXECUTE_COMMAND.name)

    private val FEATURES: Map<String, LspFeature> = LspMethods.ALL.mapNotNull { m -> m.feature?.let { m.name to it } }.toMap()

    fun isAllowed(method: String): Boolean =
        (method.startsWith(TEXT_DOCUMENT_PREFIX) && method.length > TEXT_DOCUMENT_PREFIX.length) || method in WORKSPACE_METHODS

    /** The feature that gates a known method, so a server that lacks it is skipped; null for others. */
    fun featureOf(method: String): LspFeature? = FEATURES[method]

    /** `textDocument.uri` of explicit params: the document to flush first and apply text edits to. */
    fun documentUri(params: JsonElement): String? =
        (((params as? JsonObject)?.get("textDocument") as? JsonObject)?.get("uri") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** What `applyWorkspaceEdit` does with a result: a `TextEdit[]` (formatting) or a `WorkspaceEdit`. */
    sealed interface EditShape {
        data object Nothing : EditShape
        data class Text(val edits: List<TextEdit>) : EditShape
        data class Workspace(val edit: WorkspaceEdit) : EditShape
        data object Invalid : EditShape
    }

    fun editShape(result: JsonElement): EditShape = when (result) {
        JsonNull -> EditShape.Nothing
        is JsonArray -> EditShape.Text(TextEdit.listFromJson(result))
        is JsonObject -> WorkspaceEdit.fromJson(result)?.let(EditShape::Workspace) ?: EditShape.Invalid
        else -> EditShape.Invalid
    }

    /** `showMessage` text: a string result as is, anything else as compact JSON. */
    fun messageText(result: JsonElement): String = (result as? JsonPrimitive)?.takeIf { it.isString }?.content ?: result.toString()
}

/**
 * Extension access to this workspace's language servers: the `lspRequest` action (and, later,
 * WASM `lsp.request`) after the runtime has checked the `lsp.request` capability. Routes to
 * the highest-priority ready server of the language that offers the method's feature,
 * defaults params to the caret's position params, then applies `then` like the built-in
 * commands do. Results keep the servers' guest URIs: extension paths are guest paths
 * (sdk-reference "Variables").
 */
class LspRequestGateway(
    private val ws: LspWorkspace,
    private val navigation: NavigationController,
    private val showReferences: () -> Unit,
) {

    /** The document a request is about, when there is one. */
    private data class Target(val params: JsonElement, val uri: String?, val path: String?)

    suspend fun request(language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome {
        if (!LspRequestPolicy.isAllowed(method)) return LspOutcome.Failed("$method is not available to extensions")
        val feature = LspRequestPolicy.featureOf(method)
        val session = ws.manager.sessionsFor(ws.environmentId, ws.projectId, language)
            .firstOrNull { it.state.value.isReady && (feature == null || it.supports(feature)) }
            ?: return LspOutcome.Unavailable("no running language server for $language offers $method")
        val target = withContext(Dispatchers.Main.immediate) { target(language, params) }
            ?: return LspOutcome.Unavailable("$method needs an open $language editor for its default position params")
        val answer = try {
            session.request(LspMethod(method, feature) { it }, target.params, target.uri)
        } catch (e: LspRequestException) {
            return LspOutcome.Failed("$method: ${e.code} ${e.message}")
        } ?: return LspOutcome.Unavailable("$method: no answer from ${session.key.serverId}")
        return withContext(Dispatchers.Main.immediate) { apply(then, method, answer.value, target, answer.version, session) }
    }

    private fun target(language: String, params: JsonElement?): Target? {
        if (params != null) {
            val uri = LspRequestPolicy.documentUri(params)
            val path = uri?.let(ws.documents::docForUri)?.path
            path?.let { p -> ws.tab(p)?.let { ws.documents.ensureCurrent(p, it.content) } }
            return Target(params, uri?.takeIf { path != null }, path)
        }
        val caret = ws.activeCaret() ?: return null
        val doc = ws.documents.doc(caret.path)?.takeIf { it.languageId == language } ?: return null
        ws.documents.ensureCurrent(caret.path, caret.text)
        return Target(positionParams(doc.uri, LineIndex(caret.text).position(caret.offset)), doc.uri, caret.path)
    }

    private suspend fun apply(then: LspThen, method: String, value: JsonElement, target: Target, version: Int?, session: LspSession): LspOutcome {
        when (then) {
            LspThen.NONE -> Unit
            LspThen.SHOW_MESSAGE -> ws.host.showStatus(LspRequestPolicy.messageText(value))
            LspThen.SHOW_LOCATIONS -> {
                val locs = NavTarget.listFromJson(value).mapNotNull { ws.location(it.uri, it.range) }
                when {
                    locs.isEmpty() -> ws.host.showStatus(ws.host.string(R.string.lsp_ext_no_locations, method))
                    locs.size == 1 -> ws.navigate(locs.single())
                    else -> { navigation.showLocations(method, locs); showReferences() }
                }
            }
            LspThen.APPLY_WORKSPACE_EDIT -> when (val shape = LspRequestPolicy.editShape(value)) {
                LspRequestPolicy.EditShape.Nothing -> Unit
                LspRequestPolicy.EditShape.Invalid -> return LspOutcome.Failed("$method: the result is not a WorkspaceEdit or TextEdit[]")
                is LspRequestPolicy.EditShape.Text -> {
                    val path = target.path ?: return LspOutcome.Failed("$method returned text edits without a document to apply them to")
                    if (!ws.applyToBuffer(path, version, shape.edits)) return LspOutcome.Failed("$method: the edits no longer match ${session.key.serverId}'s document")
                }
                is LspRequestPolicy.EditShape.Workspace -> {
                    val result = ws.applyWorkspaceEdit(shape.edit, ws.host.string(R.string.lsp_ext_edit_label, method))
                    if (!result.applied) return LspOutcome.Failed("$method: ${result.failureReason.orEmpty()}")
                }
            }
        }
        return LspOutcome.Result(value)
    }
}
