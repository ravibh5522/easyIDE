package dev.easyide.extensions.action

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.json.jsonEquals
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.int
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.whenclause.ContextKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/**
 * The leaf steps of the action vocabulary: substitution, the run-time capability checks only
 * knowable after substitution (extension-runtime.md sec 8.3) and the port call. Composite
 * steps (sequence, executeCommand, message actions) stay in [ActionRunner].
 */
internal class StepExecutor(
    private val host: HostPort,
    private val settings: SettingsPort,
    private val vars: VariableResolver,
) {
    suspend fun runInTerminal(ctx: ActionContext, a: Action.RunInTerminal): JsonElement {
        requireEnvironmentReady(ctx)
        val cwd = expandCwd(ctx, a.cwd)
        val env = a.env.mapValues { (_, t) -> vars.expand(t, ctx, QuoteMode.PLAIN, cwd) }
        val command = vars.expand(a.command, ctx, QuoteMode.SHELL, cwd)
        val assignments = env.entries.joinToString("") { (k, v) -> "$k=${ShellQuote.quote(v)} " }
        val line = "cd ${ShellQuote.quote(cwd)} && $assignments$command"
        val name = a.terminal?.let { vars.expand(it, ctx, QuoteMode.PLAIN, cwd) } ?: ctx.binding.title
        host.runInTerminal(TerminalRequest(ctx.owner, name, line, a.focus, a.clear))
        return JsonNull
    }

    suspend fun sandboxExec(ctx: ActionContext, a: Action.SandboxExec): JsonElement {
        val envId = requireEnvironmentReady(ctx)
        val cwd = expandCwd(ctx, a.cwd)
        val argv = a.command.map { vars.expand(it, ctx, QuoteMode.ARGV_ELEMENT, cwd) }
        val env = a.env.mapValues { (_, t) -> vars.expand(t, ctx, QuoteMode.ARGV_ELEMENT, cwd) }
        val timeoutSec = a.timeoutSec ?: settings.int(ExtensionSettings.ACTIONS_EXEC_TIMEOUT_SEC)
        val request = ExecRequest(
            ctx.owner, envId, ctx.binding.title, argv, cwd, env, a.output,
            captureLimitBytes = settings.int(ExtensionSettings.ACTIONS_CAPTURE_KB) * ExtensionSettings.KB,
            timeoutMs = timeoutSec * MS_PER_SEC,
        )
        return when (val r = host.exec(request)) {
            is ExecOutcome.Exited -> JsonObject(mapOf(
                ExecResultJson.EXIT_CODE to JsonPrimitive(r.exitCode), ExecResultJson.STDOUT to JsonPrimitive(r.stdout),
                ExecResultJson.STDERR to JsonPrimitive(r.stderr), ExecResultJson.TRUNCATED to JsonPrimitive(r.truncated),
            ))
            is ExecOutcome.TimedOut -> throw StepAbort.Fail(ActionError.TIMEOUT, "timed out after ${timeoutSec}s", r.stderrTail)
            is ExecOutcome.Unavailable -> fail(ActionError.UNAVAILABLE, r.reason)
        }
    }

    suspend fun runTask(ctx: ActionContext, a: Action.RunTask): JsonElement {
        val envId = requireEnvironmentReady(ctx)
        val task = when (val t = a.task) {
            is TaskRef.Label -> ResolvedTask.Label(vars.expand(t.label, ctx, QuoteMode.PLAIN))
            is TaskRef.Definition -> ResolvedTask.Definition(vars.expandJson(t.definition, ctx))
        }
        return when (val r = host.runTask(ctx.owner, envId, task)) {
            is TaskOutcome.Exited -> JsonPrimitive(r.exitCode)
            TaskOutcome.NotFound -> fail(ActionError.NOT_FOUND, "task not found")
            is TaskOutcome.Unavailable -> fail(ActionError.UNAVAILABLE, r.reason)
        }
    }

    suspend fun openFile(ctx: ActionContext, a: Action.OpenFile): JsonElement {
        val path = checkedPath(ctx, vars.expand(a.path, ctx, QuoteMode.PLAIN))
        if (!host.openFile(path, a.line, a.column, a.preview)) fail(ActionError.NOT_FOUND, "'$path' does not exist")
        return JsonNull
    }

    suspend fun openUrl(ctx: ActionContext, a: Action.OpenUrl): JsonElement {
        val url = vars.expand(a.url, ctx, QuoteMode.PLAIN)
        val uri = try { URI(url) } catch (e: URISyntaxException) { fail(ActionError.ARGS, "not a URL: $url") }
        if (!uri.scheme.equals(HTTPS, ignoreCase = true) || uri.host.isNullOrEmpty()) fail(ActionError.ARGS, "only https URLs can be opened")
        if (!host.confirmUrl(url)) throw StepAbort.Cancel
        host.openUrl(url)
        return JsonNull
    }

    suspend fun applyEdit(ctx: ActionContext, a: Action.ApplyEdit): JsonElement = when (val spec = a.edits) {
        is EditSpec.TextEdits -> {
            val edits = spec.edits.map { e ->
                val raw = e.path?.let { vars.expand(it, ctx, QuoteMode.PLAIN) }
                    ?: ctx.editor?.path ?: fail(ActionError.UNAVAILABLE, "no active file to edit")
                ResolvedTextEdit(checkedPath(ctx, raw), e.range, vars.expand(e.text, ctx, QuoteMode.PLAIN))
            }
            JsonPrimitive(host.applyEdits(edits))
        }
        is EditSpec.Workspace -> {
            val edit = vars.expandJson(spec.edit, ctx) as? JsonObject ?: fail(ActionError.ARGS, "edits must be a WorkspaceEdit object")
            workspaceEditUris(edit).forEach { checkedPath(ctx, uriToPath(it)) }
            JsonPrimitive(host.applyWorkspaceEdit(edit))
        }
    }

    suspend fun insertSnippet(ctx: ActionContext, a: Action.InsertSnippet): JsonElement {
        if (ctx.editor == null) fail(ActionError.UNAVAILABLE, "no active editor")
        val ok = when (val s = a.source) {
            is SnippetSource.Body -> host.insertSnippet(vars.expand(s.body, ctx, QuoteMode.PLAIN), null, null)
            is SnippetSource.Named -> host.insertSnippet(null, s.name, s.language)
        }
        if (!ok) fail(ActionError.NOT_FOUND, "snippet could not be inserted")
        return JsonNull
    }

    suspend fun setConfig(ctx: ActionContext, a: Action.SetConfig): JsonElement {
        checkConfigWrite(ctx, a.key)
        settings.write(a.key, vars.expandJson(a.value, ctx), a.target, ctx.query)
        return JsonNull
    }

    suspend fun toggleConfig(ctx: ActionContext, a: Action.ToggleConfig): JsonElement {
        checkConfigWrite(ctx, a.key)
        val current = settings.value(a.key, ctx.query)
        val index = current?.let { c -> a.values.indexOfFirst { jsonEquals(it, c) } } ?: -1
        val next = a.values[(index + 1) % a.values.size]
        settings.write(a.key, next, a.target, ctx.query)
        return next
    }

    suspend fun lspRequest(ctx: ActionContext, a: Action.LspRequest): JsonElement {
        val language = a.language ?: ctx.editor?.languageId ?: fail(ActionError.UNAVAILABLE, "no language for the request")
        val params = a.params?.let { vars.expandJson(it, ctx) }
        return when (val r = host.lspRequest(ctx.owner, language, a.method, params, a.then)) {
            is LspOutcome.Result -> r.value
            is LspOutcome.Unavailable -> fail(ActionError.UNAVAILABLE, r.reason)
            is LspOutcome.Failed -> fail(ActionError.INTERNAL, r.message)
        }
    }

    suspend fun quickPick(ctx: ActionContext, a: Action.ShowQuickPick): JsonElement {
        val items = when (val s = a.items) {
            is QuickPickSource.Items -> s.items.map { i ->
                val label = vars.expand(i.label, ctx, QuoteMode.PLAIN)
                PickItem(label, i.description?.let { vars.expand(it, ctx, QuoteMode.PLAIN) }, i.value?.let { vars.expandJson(it, ctx) } ?: JsonPrimitive(label))
            }
            is QuickPickSource.From -> itemsFrom(s.source.singleVariable?.let { vars.raw(it, ctx) } ?: JsonPrimitive(vars.expand(s.source, ctx, QuoteMode.PLAIN)))
        }
        val placeHolder = a.placeHolder?.let { vars.expand(it, ctx, QuoteMode.PLAIN) }
        val picked = host.quickPick(QuickPickRequest(ctx.binding.title, items, placeHolder, a.canPickMany)) ?: throw StepAbort.Cancel
        val value = if (a.canPickMany) JsonArray(picked) else picked.firstOrNull() ?: throw StepAbort.Cancel
        ctx.inputs[a.id] = value
        return value
    }

    suspend fun inputBox(ctx: ActionContext, a: Action.ShowInputBox): JsonElement {
        val request = InputBoxRequest(
            ctx.binding.title, a.prompt?.let { vars.expand(it, ctx, QuoteMode.PLAIN) }, a.value?.let { vars.expand(it, ctx, QuoteMode.PLAIN) },
            a.placeHolder?.let { vars.expand(it, ctx, QuoteMode.PLAIN) }, a.validate, a.password,
        )
        val text = host.inputBox(request) ?: throw StepAbort.Cancel
        if (a.password) ctx.secrets += text
        if (a.validate?.matches(text) == false) fail(ActionError.ARGS, "input does not match the required format")
        return JsonPrimitive(text).also { ctx.inputs[a.id] = it }
    }

    suspend fun openDocument(ctx: ActionContext, a: Action.OpenDocument): JsonElement {
        val uri = vars.expand(a.uri, ctx, QuoteMode.PLAIN)
        if (!uri.startsWith("ext://${ctx.owner.value}/")) fail(ActionError.CAPABILITY, "'$uri' is not a document of ${ctx.owner.value}")
        if (!host.openDocument(uri, a.group, a.preview)) fail(ActionError.UNAVAILABLE, "no document type opens '$uri'")
        return JsonNull
    }

    suspend fun revealStage(a: Action.RevealStage): JsonElement {
        host.revealStage(a.stage, a.view, a.focus)
        return JsonNull
    }

    /** Newline text, a capture result or a JSON array of strings / `{label, description?, value?}`. */
    private fun itemsFrom(v: JsonElement): List<PickItem> {
        val source = (v as? JsonObject)?.get(ExecResultJson.STDOUT) ?: v
        return when (source) {
            is JsonArray -> source.map { e ->
                val o = e as? JsonObject
                if (o == null) PickItem(e.stringOrNull ?: e.toString(), null, e)
                else {
                    val label = o["label"]?.stringOrNull ?: fail(ActionError.ARGS, "itemsFrom entries need a label")
                    PickItem(label, o["description"]?.stringOrNull, o["value"] ?: JsonPrimitive(label))
                }
            }
            else -> source.stringOrNull.orEmpty().lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.map { PickItem(it, null, JsonPrimitive(it)) }
        }
    }

    private fun requireEnvironmentReady(ctx: ActionContext): String {
        val envId = ctx.workspace.envId ?: fail(ActionError.UNAVAILABLE, "no active environment")
        if (ctx.snapshot[ContextKeys.envState.name]?.stringOrNull != ContextKeys.ENV_READY) {
            fail(ActionError.UNAVAILABLE, "environment $envId is not ready")
        }
        return envId
    }

    private suspend fun expandCwd(ctx: ActionContext, cwd: Template?): String =
        cwd?.let { vars.expand(it, ctx, QuoteMode.PLAIN) } ?: ctx.workspace.folder

    /** Resolves a guest path; outside `/workspace` needs `fs.outsideProject` (sdk-reference). */
    private fun checkedPath(ctx: ActionContext, raw: String): String {
        val folder = ctx.workspace.folder
        val path = GuestPaths.resolve(folder, raw) ?: fail(ActionError.ARGS, "invalid path '$raw'")
        if (!GuestPaths.isInside(folder, path) && !ctx.granted.satisfies(Capability.FsOutsideProject)) {
            fail(ActionError.CAPABILITY, "'$path' is outside the project and fs.outsideProject is not granted")
        }
        return path
    }

    /**
     * Own keys are free; other keys need `ui.settings`; protected keys are refused even with
     * it (sdk-reference "Always on, no key").
     */
    private fun checkConfigWrite(ctx: ActionContext, key: String) {
        if (ExtensionPolicy.isUnwritable(key)) fail(ActionError.CAPABILITY, "'$key' cannot be changed by extensions")
        if (key !in ctx.binding.ownSettings && !ctx.granted.satisfies(Capability.UiSettings)) {
            fail(ActionError.CAPABILITY, "'$key' belongs to another owner and ui.settings is not granted")
        }
    }

    private fun workspaceEditUris(edit: JsonObject): List<String> {
        val uris = ArrayList<String>()
        (edit["changes"] as? JsonObject)?.keys?.let(uris::addAll)
        (edit["documentChanges"] as? JsonArray)?.forEach { change ->
            val o = change as? JsonObject ?: return@forEach
            listOfNotNull(
                (o["textDocument"] as? JsonObject)?.get("uri"), o["uri"], o["oldUri"], o["newUri"],
            ).mapNotNullTo(uris) { it.stringOrNull }
        }
        return uris
    }

    private fun uriToPath(uri: String): String {
        val parsed = try { URI(uri) } catch (e: URISyntaxException) { fail(ActionError.ARGS, "not a URI: $uri") }
        if (!parsed.scheme.equals(FILE, ignoreCase = true) || parsed.path == null) fail(ActionError.ARGS, "only file URIs can be edited: $uri")
        return parsed.path
    }

    private companion object {
        const val HTTPS = "https"
        const val FILE = "file"
        const val MS_PER_SEC = 1000L
    }
}
