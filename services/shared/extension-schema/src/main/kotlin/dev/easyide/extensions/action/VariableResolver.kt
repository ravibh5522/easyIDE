package dev.easyide.extensions.action

import dev.easyide.extensions.json.asText
import dev.easyide.extensions.settings.SettingsPort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Early exit of a step. Thrown by the resolver and step code and caught once per step in
 * [ActionRunner], so deep helpers (a `${input:}` prompt inside an argv element) can end
 * the step without threading a result type through every call.
 */
internal sealed class StepAbort : Exception(null, null, false, false) {
    class Fail(val error: ActionError, override val message: String, val stderrTail: String? = null) : StepAbort() {
        /** Step path of the innermost failing step, set as the failure unwinds. */
        var path: String? = null
    }
    object Cancel : StepAbort()
}

internal fun fail(error: ActionError, message: String): Nothing = throw StepAbort.Fail(error, message)

/**
 * Expands [Template]s for one invocation (sdk-reference "Variables"). Values come from the
 * frozen context, settings, the environment's shell env, prompts and other commands; the
 * two callbacks let the runner own command execution and prompting.
 */
internal class VariableResolver(
    private val settings: SettingsPort,
    private val host: WorkspacePort,
    private val runCommand: suspend (ActionContext, String) -> JsonElement,
    private val resolveInput: suspend (ActionContext, String) -> JsonElement,
) {
    /** [cwd] is the step's start directory for `${cwd}` (default `${workspaceFolder}`). */
    suspend fun expand(t: Template, ctx: ActionContext, mode: QuoteMode, cwd: String? = null): String {
        val sb = StringBuilder()
        for (seg in t.segments) {
            when (seg) {
                is Template.Segment.Literal -> sb.append(seg.text)
                is Template.Segment.Var -> {
                    val value = text(seg.ref, ctx, cwd)
                    if (value.contains('\u0000')) fail(ActionError.ARGS, "a substituted value contains NUL")
                    sb.append(if (mode == QuoteMode.SHELL) ShellQuote.quote(value) else value)
                }
            }
        }
        if (mode == QuoteMode.ARGV_ELEMENT && sb.contains('\u0000')) fail(ActionError.ARGS, "an argument contains NUL")
        return sb.toString()
    }

    /** JSON with templated string leaves; a leaf that is one variable keeps the variable's JSON type. */
    suspend fun expandJson(t: JsonTemplate, ctx: ActionContext): JsonElement = when (t) {
        is JsonTemplate.Leaf -> t.value
        is JsonTemplate.Text -> t.template.singleVariable?.let { raw(it, ctx) } ?: JsonPrimitive(expand(t.template, ctx, QuoteMode.PLAIN))
        is JsonTemplate.Arr -> JsonArray(t.items.map { expandJson(it, ctx) })
        is JsonTemplate.Obj -> JsonObject(t.fields.mapValues { (_, v) -> expandJson(v, ctx) })
    }

    /** The JSON value of a variable, for `itemsFrom` and single-variable JSON leaves. */
    suspend fun raw(ref: VariableRef, ctx: ActionContext): JsonElement = when (ref) {
        is VariableRef.Result -> ctx.results[ref.name] ?: fail(ActionError.ARGS, "\${result:${ref.name}} is not bound")
        is VariableRef.Input -> input(ref.id, ctx)
        is VariableRef.Config -> settings.value(ref.key, ctx.query)?.takeUnless { it == JsonNull }
            ?: fail(ActionError.ARGS, "setting '${ref.key}' has no value")
        is VariableRef.Command -> command(ref.id, ctx)
        is VariableRef.Arg -> arg(ref.path, ctx)
        else -> JsonPrimitive(text(ref, ctx, null))
    }

    private suspend fun text(ref: VariableRef, ctx: ActionContext, cwd: String?): String = when (ref) {
        is VariableRef.Predefined -> predefined(ref.variable, ctx, cwd)
        is VariableRef.Env -> {
            val envId = ctx.workspace.envId ?: fail(ActionError.UNAVAILABLE, "no active environment for \${env:${ref.name}}")
            host.shellEnvironment(envId)[ref.name] ?: fail(ActionError.ARGS, "\${env:${ref.name}} is not set in environment $envId")
        }
        is VariableRef.Result -> resultText(raw(ref, ctx))
        else -> raw(ref, ctx).asText()
    }

    /** Object keys and array indexes only; a missing field is a step error like any unresolvable variable. */
    private fun arg(path: String, ctx: ActionContext): JsonElement {
        var at: JsonElement? = ctx.args
        for (segment in path.split('.')) {
            at = when (at) {
                is JsonObject -> at[segment]
                is JsonArray -> segment.toIntOrNull()?.let { at.getOrNull(it) }
                else -> null
            }
        }
        return at?.takeUnless { it == JsonNull } ?: fail(ActionError.ARGS, "\${arg:$path} is not set")
    }

    private suspend fun input(id: String, ctx: ActionContext): JsonElement =
        ctx.inputs[id] ?: resolveInput(ctx, id).also { ctx.inputs[id] = it }

    private suspend fun command(id: String, ctx: ActionContext): JsonElement =
        runCommand(ctx, id).takeUnless { it == JsonNull } ?: fail(ActionError.ARGS, "\${command:$id} returned no value")

    /** A `sandboxExec` capture substitutes its stdout minus one trailing newline; other values as text. */
    private fun resultText(v: JsonElement): String {
        val capture = v as? JsonObject
        val stdout = capture?.get(ExecResultJson.STDOUT)
        if (stdout is JsonPrimitive && stdout.isString && ExecResultJson.EXIT_CODE in capture) return stdout.content.removeSuffix("\n")
        return v.asText()
    }

    private fun predefined(v: PredefinedVariable, ctx: ActionContext, cwd: String?): String {
        val ws = ctx.workspace
        fun editor() = ctx.editor ?: fail(ActionError.UNAVAILABLE, "\${${v.wire}} needs an active editor")
        fun file() = editor().path ?: fail(ActionError.UNAVAILABLE, "\${${v.wire}} needs a file in the active editor")
        fun relative(path: String) = GuestPaths.relativeTo(ws.folder, path) ?: fail(ActionError.ARGS, "'$path' is outside the workspace")
        return when (v) {
            PredefinedVariable.WORKSPACE_FOLDER -> ws.folder
            PredefinedVariable.WORKSPACE_FOLDER_BASENAME -> ws.name
            PredefinedVariable.FILE -> file()
            PredefinedVariable.RELATIVE_FILE -> relative(file())
            PredefinedVariable.FILE_BASENAME -> GuestPaths.basename(file())
            PredefinedVariable.FILE_BASENAME_NO_EXTENSION -> GuestPaths.stem(file())
            PredefinedVariable.FILE_EXTNAME -> GuestPaths.extname(file())
            PredefinedVariable.FILE_DIRNAME -> GuestPaths.dirname(file())
            PredefinedVariable.RELATIVE_FILE_DIRNAME -> relative(GuestPaths.dirname(file())).ifEmpty { "." }
            PredefinedVariable.FILE_WORKSPACE_FOLDER -> { relative(file()); ws.folder }
            PredefinedVariable.LINE_NUMBER -> editor().line.toString()
            PredefinedVariable.COLUMN -> editor().column.toString()
            PredefinedVariable.SELECTED_TEXT -> editor().selectedText
            PredefinedVariable.CURRENT_WORD -> editor().currentWord
            PredefinedVariable.LINE_TEXT -> editor().lineText
            PredefinedVariable.LANGUAGE_ID -> editor().languageId ?: fail(ActionError.UNAVAILABLE, "the active editor has no language")
            PredefinedVariable.CWD -> cwd ?: ws.folder
            PredefinedVariable.PATH_SEPARATOR -> "/"
            PredefinedVariable.EXTENSION_PATH -> ctx.binding.extensionPath
            PredefinedVariable.ENV_ID -> ws.envId ?: fail(ActionError.UNAVAILABLE, "no active environment")
            PredefinedVariable.ENV_NAME -> ws.envName ?: fail(ActionError.UNAVAILABLE, "no active environment")
        }
    }
}

/** Field names of the `sandboxExec` result object (sdk-reference `{exitCode, stdout, stderr}`). */
object ExecResultJson {
    const val EXIT_CODE = "exitCode"
    const val STDOUT = "stdout"
    const val STDERR = "stderr"
    const val TRUNCATED = "truncated"
}

/** POSIX path helpers for guest paths (always `/`, never the host's separator). */
object GuestPaths {
    /** Resolves [path] against [folder] when relative and removes `.`/`..` segments; null if it escapes `/`. */
    fun resolve(folder: String, path: String): String? {
        val joined = if (path.startsWith("/")) path else "$folder/$path"
        val parts = ArrayList<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun isInside(folder: String, path: String): Boolean = path == folder || path.startsWith("$folder/")

    fun relativeTo(folder: String, path: String): String? = when {
        path == folder -> ""
        path.startsWith("$folder/") -> path.substring(folder.length + 1)
        else -> null
    }

    fun basename(path: String): String = path.substringAfterLast('/')
    fun dirname(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    /** `.bashrc` has no extension (Node's `path.extname`, which VS Code uses). */
    fun extname(path: String): String {
        val base = basename(path)
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) "" else base.substring(dot)
    }

    fun stem(path: String): String = basename(path).removeSuffix(extname(path))
}
