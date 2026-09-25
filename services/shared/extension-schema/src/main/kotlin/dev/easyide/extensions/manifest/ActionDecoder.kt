package dev.easyide.extensions.manifest

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.ActionType
import dev.easyide.extensions.action.EditSpec
import dev.easyide.extensions.action.ExecOutput
import dev.easyide.extensions.action.InputSpec
import dev.easyide.extensions.action.JsonTemplate
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.MessageAction
import dev.easyide.extensions.action.MessageSeverity
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.action.PickOption
import dev.easyide.extensions.action.QuickPickItem
import dev.easyide.extensions.action.QuickPickSource
import dev.easyide.extensions.action.SnippetSource
import dev.easyide.extensions.action.TaskRef
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.action.TextEdit
import dev.easyide.extensions.action.TextPosition
import dev.easyide.extensions.action.TextRange
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.settings.ConfigTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Decodes `easyide.actions` / `easyide.inputs` entries into the typed [Action] model. */
internal class ActionDecoder(private val ctx: DecodeContext) {

    fun action(o: JsonObject, p: String): Action? {
        val type = ActionType.parse(o.reqStr("type")) ?: error("schema guarantees a known action type")
        val bindAs = o.str("as")
        fun t(key: String) = ctx.templateAt(o, key, p)
        fun reqT(key: String) = ctx.template(o.reqStr(key), JsonPointer.child(p, key))
        return when (type) {
            ActionType.RUN_IN_TERMINAL -> Action.RunInTerminal(
                reqT("command"), t("cwd"), ctx.envMap(o.obj("env"), JsonPointer.child(p, "env")), t("terminal"),
                o.bool("focus") ?: true, o.bool("clear") ?: false, bindAs,
            )
            ActionType.RUN_TASK -> {
                val task = o["task"]
                val ref = if (task is JsonObject) TaskRef.Definition(json(task, JsonPointer.child(p, "task")))
                else TaskRef.Label(reqT("task"))
                Action.RunTask(ref, bindAs)
            }
            ActionType.SANDBOX_EXEC -> Action.SandboxExec(
                o.arr("command")!!.mapIndexed { i, e -> ctx.template(e.stringOrNull.orEmpty(), JsonPointer.index(JsonPointer.child(p, "command"), i)) },
                t("cwd"), ctx.envMap(o.obj("env"), JsonPointer.child(p, "env")), o.int("timeoutSec"),
                ExecOutput.entries.first { it.wire == o.reqStr("output") }, bindAs,
            )
            ActionType.OPEN_FILE -> Action.OpenFile(reqT("path"), o.int("line"), o.int("column"), o.bool("preview") ?: false, bindAs)
            ActionType.OPEN_URL -> Action.OpenUrl(reqT("url"), bindAs)
            ActionType.APPLY_EDIT -> Action.ApplyEdit(edits(o["edits"]!!, JsonPointer.child(p, "edits")), bindAs)
            ActionType.INSERT_SNIPPET -> snippet(o, p, bindAs)
            ActionType.SET_CONFIG -> Action.SetConfig(
                o.reqStr("key"), json(o["value"]!!, JsonPointer.child(p, "value")),
                ConfigTarget.parse(o.reqStr("target"))!!, bindAs,
            )
            ActionType.TOGGLE_CONFIG -> Action.ToggleConfig(
                o.reqStr("key"), o.arr("values")?.toList() ?: listOf(JsonPrimitive(true), JsonPrimitive(false)),
                o.str("target")?.let(ConfigTarget::parse) ?: ConfigTarget.USER, bindAs,
            )
            ActionType.LSP_REQUEST -> Action.LspRequest(
                o.reqStr("method"), o["params"]?.let { json(it, JsonPointer.child(p, "params")) }, o.str("language"),
                o.str("then")?.let { w -> LspThen.entries.first { it.wire == w } } ?: LspThen.NONE, bindAs,
            )
            ActionType.EXECUTE_COMMAND -> Action.ExecuteCommand(
                o.reqStr("command"), o["args"]?.let { json(it, JsonPointer.child(p, "args")) }, bindAs,
            )
            ActionType.SHOW_QUICK_PICK -> quickPick(o, p, bindAs)
            ActionType.SHOW_INPUT_BOX -> Action.ShowInputBox(
                o.reqStr("id"), t("prompt"), t("value"), t("placeHolder"),
                o.str("validate")?.let { ctx.regex(it, JsonPointer.child(p, "validate")) }, o.bool("password") ?: false, bindAs,
            )
            ActionType.SHOW_MESSAGE -> Action.ShowMessage(
                reqT("text"), o.str("severity")?.let { w -> MessageSeverity.entries.first { it.wire == w } } ?: MessageSeverity.INFO,
                o.objs("actions").mapIndexed { i, a ->
                    val ap = JsonPointer.index(JsonPointer.child(p, "actions"), i)
                    MessageAction(ctx.template(a.reqStr("title"), JsonPointer.child(ap, "title")), a.obj("action")?.let { action(it, JsonPointer.child(ap, "action")) })
                },
                bindAs,
            )
            ActionType.OPEN_DOCUMENT -> Action.OpenDocument(
                reqT("uri"), o.str("group")?.let(OpenGroup::parse) ?: OpenGroup.ACTIVE, o.bool("preview") ?: true, bindAs,
            )
            ActionType.REVEAL_STAGE -> Action.RevealStage(o.reqStr("stage"), o.str("view"), o.bool("focus") ?: true, bindAs)
            ActionType.SEQUENCE -> Action.Sequence(
                o.objs("steps").mapIndexedNotNull { i, s -> action(s, JsonPointer.index(JsonPointer.child(p, "steps"), i)) },
                o.bool("continueOnError") ?: false, bindAs,
            )
        }
    }

    fun inputs(arr: JsonArray?, p: String): Map<String, InputSpec> {
        val out = LinkedHashMap<String, InputSpec>()
        arr?.forEachIndexed { i, e ->
            val o = e as JsonObject
            val ip = JsonPointer.index(p, i)
            val id = o.reqStr("id")
            if (id in out) { ctx.error(DiagnosticCode.DUPLICATE_ID, JsonPointer.child(ip, "id"), "duplicate input '$id'"); return@forEachIndexed }
            out[id] = when (o.reqStr("type")) {
                "promptString" -> InputSpec.PromptString(id, o.str("description"), o.str("default"), o.bool("password") ?: false)
                "pickString" -> InputSpec.PickString(id, o.str("description"), o.arr("options")!!.map(::pickOption), o.str("default"))
                else -> InputSpec.Command(id, o.reqStr("command"), o["args"])
            }
        }
        return out
    }

    private fun pickOption(e: JsonElement): PickOption {
        e.stringOrNull?.let { return PickOption(it, it) }
        val o = e as JsonObject
        val value = o.reqStr("value")
        return PickOption(o.str("label") ?: value, value)
    }

    private fun snippet(o: JsonObject, p: String, bindAs: String?): Action? {
        val body = o.str("snippet")
        val name = o.str("name")
        if ((body == null) == (name == null)) {
            ctx.error(DiagnosticCode.ONE_OF, p, "insertSnippet needs exactly one of 'snippet' or 'name'")
            return null
        }
        val source = if (body != null) SnippetSource.Body(ctx.template(body, JsonPointer.child(p, "snippet")))
        else SnippetSource.Named(name!!, o.str("language"))
        return Action.InsertSnippet(source, bindAs)
    }

    private fun quickPick(o: JsonObject, p: String, bindAs: String?): Action? {
        val items = o.arr("items")
        val from = o.str("itemsFrom")
        if ((items == null) == (from == null)) {
            ctx.error(DiagnosticCode.ONE_OF, p, "showQuickPick needs exactly one of 'items' or 'itemsFrom'")
            return null
        }
        val source = if (from != null) QuickPickSource.From(ctx.template(from, JsonPointer.child(p, "itemsFrom")))
        else QuickPickSource.Items(items!!.mapIndexed { i, e ->
            val it = e as JsonObject
            val ip = JsonPointer.index(JsonPointer.child(p, "items"), i)
            QuickPickItem(
                ctx.template(it.reqStr("label"), JsonPointer.child(ip, "label")),
                ctx.templateAt(it, "description", ip),
                it["value"]?.let { v -> json(v, JsonPointer.child(ip, "value")) },
            )
        })
        return Action.ShowQuickPick(o.reqStr("id"), source, ctx.templateAt(o, "placeHolder", p), o.bool("canPickMany") ?: false, bindAs)
    }

    private fun edits(e: JsonElement, p: String): EditSpec {
        if (e is JsonObject) return EditSpec.Workspace(json(e, p))
        return EditSpec.TextEdits((e as JsonArray).mapIndexed { i, x ->
            val o = x as JsonObject
            val ep = JsonPointer.index(p, i)
            val range = o.obj("range")!!
            TextEdit(
                ctx.templateAt(o, "path", ep),
                TextRange(position(range.obj("start")!!), position(range.obj("end")!!)),
                ctx.template(o.reqStr("text"), JsonPointer.child(ep, "text")),
            )
        })
    }

    private fun position(o: JsonObject) = TextPosition(o.int("line")!!, o.int("character")!!)

    private fun json(value: JsonElement, p: String): JsonTemplate =
        JsonTemplate.of(value, { rel, err -> ctx.error(DiagnosticCode.TEMPLATE, p + rel, "offset ${err.offset}: ${err.message}") })

    /** Templates of an action tree, flattened; used to audit `${command:}` / `${input:}` references. */
    companion object {
        fun templates(a: Action): List<Template> = when (a) {
            is Action.RunInTerminal -> listOfNotNull(a.command, a.cwd, a.terminal) + a.env.values
            is Action.RunTask -> when (val t = a.task) { is TaskRef.Label -> listOf(t.label); is TaskRef.Definition -> t.definition.templates() }
            is Action.SandboxExec -> a.command + listOfNotNull(a.cwd) + a.env.values
            is Action.OpenFile -> listOf(a.path)
            is Action.OpenUrl -> listOf(a.url)
            is Action.ApplyEdit -> when (val e = a.edits) {
                is EditSpec.TextEdits -> e.edits.flatMap { listOfNotNull(it.path, it.text) }
                is EditSpec.Workspace -> e.edit.templates()
            }
            is Action.InsertSnippet -> (a.source as? SnippetSource.Body)?.let { listOf(it.body) } ?: emptyList()
            is Action.SetConfig -> a.value.templates()
            is Action.ToggleConfig -> emptyList()
            is Action.LspRequest -> a.params?.templates() ?: emptyList()
            is Action.ExecuteCommand -> a.args?.templates() ?: emptyList()
            is Action.ShowQuickPick -> listOfNotNull(a.placeHolder) + when (val s = a.items) {
                is QuickPickSource.From -> listOf(s.source)
                is QuickPickSource.Items -> s.items.flatMap { listOfNotNull(it.label, it.description) + (it.value?.templates() ?: emptyList()) }
            }
            is Action.ShowInputBox -> listOfNotNull(a.prompt, a.value, a.placeHolder)
            is Action.ShowMessage -> listOf(a.text) + a.actions.flatMap { m -> listOf(m.title) + (m.action?.let(::templates) ?: emptyList()) }
            is Action.OpenDocument -> listOf(a.uri)
            is Action.RevealStage -> emptyList()
            is Action.Sequence -> a.steps.flatMap(::templates)
        }
    }
}
