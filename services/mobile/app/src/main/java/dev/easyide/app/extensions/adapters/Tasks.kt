package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.action.ShellQuote
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One runnable task from the project's `.easyide/tasks.json` (VS Code `tasks.json` shape,
 * `version` 2.0.0): `shell` tasks run their command line through the guest shell,
 * `process` tasks exec the command with `args` directly. [definition] is the raw entry,
 * matched field by field when an action names a task by definition (`runTask` with
 * `{type, ...}`).
 */
data class TaskSpec(
    val label: String,
    val type: String,
    val command: String,
    val args: List<String>,
    val cwd: String?,
    val env: Map<String, String>,
    val definition: JsonObject,
) {
    /**
     * The argv to run, after [expand] substituted variables (`${workspaceFolder}`, ...)
     * into the command and each argument. Shell tasks quote each substituted argument, as
     * VS Code does; the command line itself is the author's.
     */
    fun argv(expand: (String) -> String): List<String> = when (type) {
        Tasks.TYPE_SHELL -> listOf(Tasks.GUEST_SHELL, Tasks.SHELL_FLAG, (listOf(expand(command)) + args.map { ShellQuote.quote(expand(it)) }).joinToString(" "))
        else -> listOf(expand(command)) + args.map(expand)
    }
}

/** Parses and matches `.easyide/tasks.json` (EXT-30's task source; problem matchers are not applied yet). */
object Tasks {
    const val FILE = ".easyide/tasks.json"
    const val TYPE_SHELL = "shell"
    const val TYPE_PROCESS = "process"
    internal const val GUEST_SHELL = "/bin/sh"
    internal const val SHELL_FLAG = "-c"

    /**
     * The runnable tasks in [text] (JSONC). Entries of other types (a contributed
     * `taskDefinitions` type) need a task provider to turn them into a process, which a
     * declarative pack cannot supply; they are skipped, like entries without a label.
     */
    fun parse(text: String): List<TaskSpec>? {
        val root = (JsonText.parseLenient(text) as? JsonParse.Ok)?.value as? JsonObject ?: return null
        val tasks = root[TASKS] as? JsonArray ?: return emptyList()
        return tasks.mapNotNull { (it as? JsonObject)?.let(::decode) }
    }

    private fun decode(o: JsonObject): TaskSpec? {
        val label = o[LABEL]?.stringOrNull ?: return null
        val type = o[TYPE]?.stringOrNull ?: TYPE_PROCESS
        val command = o[COMMAND]?.stringOrNull ?: return null
        val args = (o[ARGS] as? JsonArray)?.map { it.stringOrNull ?: return null }.orEmpty()
        val options = o[OPTIONS] as? JsonObject
        val env = (options?.get(ENV) as? JsonObject)?.mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }?.toMap().orEmpty()
        if (type != TYPE_SHELL && type != TYPE_PROCESS) return null
        return TaskSpec(label, type, command, args, options?.get(CWD)?.stringOrNull, env, o)
    }

    /** VS Code identifies a task by definition when every field of [definition] equals the entry's. */
    fun matching(tasks: List<TaskSpec>, definition: JsonElement): TaskSpec? {
        val d = definition as? JsonObject ?: return null
        return tasks.firstOrNull { t -> d.all { (k, v) -> t.definition[k] == v } }
    }

    private const val TASKS = "tasks"
    private const val LABEL = "label"
    private const val TYPE = "type"
    private const val COMMAND = "command"
    private const val ARGS = "args"
    private const val OPTIONS = "options"
    private const val ENV = "env"
    private const val CWD = "cwd"
}
