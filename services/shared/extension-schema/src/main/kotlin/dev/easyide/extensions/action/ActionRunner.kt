package dev.easyide.extensions.action

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.capability.CapabilityRules
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.whenclause.ContextSnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Result of one step for L2 host functions ([ActionRunner.runStep]). */
sealed interface StepResult {
    data class Value(val value: JsonElement) : StepResult
    data object Cancelled : StepResult
    data class Failed(val error: ActionError, val message: String, val stderrTail: String?) : StepResult
}

/**
 * Runs the L1 action vocabulary for extension commands and serves the same steps to L2 host
 * functions, so both layers go through one set of checks (extension-runtime.md sec 8).
 *
 * Per step, before any effect: the static capability need against the granted set, then
 * the checks only knowable after substitution. A failing step aborts its sequence (unless
 * `continueOnError`) and is logged with its step path; a dismissed prompt ends the run
 * silently. Coroutine cancellation propagates untouched: whoever cancelled wants silence.
 */
class ActionRunner(
    private val catalog: CommandCatalog,
    private val host: HostPort,
    private val settings: SettingsPort,
    private val context: () -> ContextSnapshot,
    private val log: ExtensionLog,
    private val logic: LogicHost,
) {
    private val running: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()
    private val vars = VariableResolver(settings, host, ::commandVariable, ::resolveInput)
    private val steps = StepExecutor(host, settings, vars)

    /** Runs [commandId] as the user invoked it (palette, menu, keybinding, key row). */
    suspend fun run(commandId: String, args: JsonElement? = null): ActionOutcome =
        invoke(commandId, args, depth = 0, parent = null)

    /**
     * Runs one step for an L2 host function with [ctx] from [newContext]. Composite steps
     * work as in a manifest, including nested `executeCommand` with the target's grants.
     */
    suspend fun runStep(ctx: ActionContext, action: Action): StepResult = try {
        StepResult.Value(execute(ctx, action, action.type.wire))
    } catch (a: StepAbort.Fail) {
        StepResult.Failed(a.error, ctx.redact(a.message), a.stderrTail?.let(ctx::redact))
    } catch (a: StepAbort.Cancel) {
        StepResult.Cancelled
    }

    /** A fresh invocation context for [binding], frozen now. */
    fun newContext(binding: CommandBinding, args: JsonElement?): ActionContext =
        ActionContext(binding, args, context(), host.activeEditor(), host.workspace(), depth = 0)

    private suspend fun invoke(commandId: String, args: JsonElement?, depth: Int, parent: ActionContext?): ActionOutcome {
        val binding = catalog.binding(commandId) ?: return builtIn(commandId, args)
        val key = binding.owner.value to commandId
        if (!running.add(key)) {
            return failed(binding, "", ActionError.UNAVAILABLE, "${binding.title} is already running", null)
        }
        try {
            return when (val h = binding.handler) {
                CommandHandler.Logic -> logic(binding, args)
                is CommandHandler.Declarative -> {
                    // A nested run keeps the invocation's frozen snapshot and editor.
                    val ctx = ActionContext(
                        binding, args, parent?.snapshot ?: context(), parent?.editor ?: host.activeEditor(),
                        parent?.workspace ?: host.workspace(), depth,
                    )
                    try {
                        ActionOutcome.Done(execute(ctx, h.action, h.action.type.wire))
                    } catch (a: StepAbort.Fail) {
                        failed(binding, a.path ?: h.action.type.wire, a.error, ctx.redact(a.message), a.stderrTail?.let(ctx::redact))
                    } catch (a: StepAbort.Cancel) {
                        ActionOutcome.Cancelled
                    }
                }
            }
        } finally {
            running.remove(key)
        }
    }

    private suspend fun execute(ctx: ActionContext, action: Action, path: String): JsonElement {
        val missing = ctx.granted.missing(ownNeeds(action))
        if (missing.isNotEmpty()) {
            throw StepAbort.Fail(ActionError.CAPABILITY, "requires ${missing.joinToString { it.id }}, which is not granted").at(path)
        }
        val value = try {
            when (action) {
                is Action.RunInTerminal -> steps.runInTerminal(ctx, action)
                is Action.RunTask -> steps.runTask(ctx, action)
                is Action.SandboxExec -> steps.sandboxExec(ctx, action)
                is Action.OpenFile -> steps.openFile(ctx, action)
                is Action.OpenUrl -> steps.openUrl(ctx, action)
                is Action.ApplyEdit -> steps.applyEdit(ctx, action)
                is Action.InsertSnippet -> steps.insertSnippet(ctx, action)
                is Action.SetConfig -> steps.setConfig(ctx, action)
                is Action.ToggleConfig -> steps.toggleConfig(ctx, action)
                is Action.LspRequest -> steps.lspRequest(ctx, action)
                is Action.ShowQuickPick -> steps.quickPick(ctx, action)
                is Action.ShowInputBox -> steps.inputBox(ctx, action)
                is Action.RevealStage -> steps.revealStage(action)
                is Action.OpenDocument -> steps.openDocument(ctx, action)
                is Action.ExecuteCommand -> executeCommand(ctx, action)
                is Action.ShowMessage -> showMessage(ctx, action, path)
                is Action.Sequence -> sequence(ctx, action, path)
            }
        } catch (f: StepAbort.Fail) {
            throw f.at(path)
        }
        action.bindAs?.let { ctx.results[it] = value }
        return value
    }

    /** Leaf needs only: composite steps check each child as it runs. */
    private fun ownNeeds(action: Action) = when (action) {
        is Action.Sequence, is Action.ShowMessage -> emptySet()
        else -> CapabilityRules.required(action)
    }

    private suspend fun sequence(ctx: ActionContext, a: Action.Sequence, path: String): JsonElement {
        var last: JsonElement = JsonNull
        a.steps.forEachIndexed { i, step ->
            val stepPath = "$path.steps[$i].${step.type.wire}"
            last = try {
                execute(ctx, step, stepPath)
            } catch (f: StepAbort.Fail) {
                if (!a.continueOnError) throw f
                logFailure(ctx.binding, f.path ?: stepPath, f.error, ctx.redact(f.message))
                JsonNull
            }
        }
        return last
    }

    private suspend fun showMessage(ctx: ActionContext, a: Action.ShowMessage, path: String): JsonElement {
        val titles = a.actions.map { vars.expand(it.title, ctx, QuoteMode.PLAIN) }
        val text = vars.expand(a.text, ctx, QuoteMode.PLAIN)
        val chosen = host.showMessage(MessageRequest(ctx.owner, text, a.severity, titles)) ?: return JsonNull
        val index = titles.indexOf(chosen)
        a.actions.getOrNull(index)?.action?.let { execute(ctx, it, "$path.actions[$index].${it.type.wire}") }
        return JsonPrimitive(chosen)
    }

    /** The target runs with its own owner's grants and bindings, never the caller's (sec 8.3). */
    private suspend fun executeCommand(ctx: ActionContext, a: Action.ExecuteCommand): JsonElement {
        val args = a.args?.let { vars.expandJson(it, ctx) }
        return nested(ctx, a.command, args)
    }

    private suspend fun commandVariable(ctx: ActionContext, commandId: String): JsonElement = nested(ctx, commandId, null)

    private suspend fun nested(ctx: ActionContext, commandId: String, args: JsonElement?): JsonElement {
        if (ctx.depth >= ExtensionPolicy.MAX_COMMAND_VARIABLE_DEPTH) {
            fail(ActionError.LIMIT, "command nesting deeper than ${ExtensionPolicy.MAX_COMMAND_VARIABLE_DEPTH}")
        }
        return when (val r = invoke(commandId, args, ctx.depth + 1, ctx)) {
            is ActionOutcome.Done -> r.value
            ActionOutcome.Cancelled -> throw StepAbort.Cancel
            is ActionOutcome.Failed -> throw StepAbort.Fail(r.error, "$commandId: ${r.message}", r.stderrTail)
        }
    }

    /** `${input:id}` not bound by a prompt step: run the matching `easyide.inputs` entry once. */
    private suspend fun resolveInput(ctx: ActionContext, id: String): JsonElement {
        val title = ctx.binding.title
        return when (val spec = ctx.binding.inputs[id] ?: fail(ActionError.ARGS, "\${input:$id} is not defined")) {
            is InputSpec.PromptString -> {
                val text = host.inputBox(InputBoxRequest(title, spec.description, spec.default, null, null, spec.password))
                    ?: throw StepAbort.Cancel
                if (spec.password) ctx.secrets += text
                JsonPrimitive(text)
            }
            is InputSpec.PickString -> {
                val items = spec.options.map { PickItem(it.label, null, JsonPrimitive(it.value)) }
                host.quickPick(QuickPickRequest(title, items, spec.description, canPickMany = false))?.firstOrNull() ?: throw StepAbort.Cancel
            }
            is InputSpec.Command -> nested(ctx, spec.command, spec.args)
        }
    }

    private suspend fun builtIn(commandId: String, args: JsonElement?): ActionOutcome = when (val r = host.executeBuiltIn(commandId, args)) {
        is CommandOutcome.Done -> ActionOutcome.Done(r.value)
        CommandOutcome.Cancelled -> ActionOutcome.Cancelled
        CommandOutcome.NotFound -> ActionOutcome.Failed("", ActionError.NOT_FOUND, "unknown command '$commandId'")
        is CommandOutcome.Failed -> ActionOutcome.Failed("", r.error, r.message)
    }

    private suspend fun logic(binding: CommandBinding, args: JsonElement?): ActionOutcome = when (val r = logic.executeCommand(binding.owner, binding.commandId, args)) {
        is CommandOutcome.Done -> ActionOutcome.Done(r.value)
        CommandOutcome.Cancelled -> ActionOutcome.Cancelled
        CommandOutcome.NotFound -> failed(binding, "", ActionError.NOT_FOUND, "no handler registered for ${binding.commandId}", null)
        is CommandOutcome.Failed -> failed(binding, "", r.error, r.message, null)
    }

    private fun failed(binding: CommandBinding, path: String, error: ActionError, message: String, stderrTail: String?): ActionOutcome.Failed {
        logFailure(binding, path, error, message, stderrTail)
        return ActionOutcome.Failed(path, error, message, stderrTail)
    }

    private fun logFailure(binding: CommandBinding, path: String, error: ActionError, message: String, stderrTail: String? = null) {
        val tail = stderrTail?.takeLast(ExtensionPolicy.STDERR_TAIL_CHARS)?.let { "\n$it" }.orEmpty()
        log.append(LogEntry(binding.owner, LogLevel.ERROR, "${binding.title} (${binding.commandId}) failed at ${path.ifEmpty { "-" }}: ${error.code} $message$tail"))
    }
}

/** Records where a failure happened (the innermost step keeps its path). */
internal fun StepAbort.Fail.at(path: String): StepAbort.Fail = also { if (this.path == null) this.path = path }
