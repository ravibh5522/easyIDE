package dev.easyide.extensions.action

import dev.easyide.extensions.settings.ConfigTarget
import kotlinx.serialization.json.JsonElement

/**
 * The fixed L1 action vocabulary (sdk-reference "Action vocabulary"), one type per row.
 * The set is closed on purpose: a new action is permanent API (0013, R-API-11), so the
 * runner can check every case exhaustively. [bindAs] is the manifest's `"as"`.
 */
sealed interface Action {
    val bindAs: String?
    val type: ActionType

    data class RunInTerminal(
        val command: Template, val cwd: Template?, val env: Map<String, Template>, val terminal: Template?,
        val focus: Boolean, val clear: Boolean, override val bindAs: String?,
    ) : Action { override val type get() = ActionType.RUN_IN_TERMINAL }

    data class RunTask(val task: TaskRef, override val bindAs: String?) : Action {
        override val type get() = ActionType.RUN_TASK
    }

    data class SandboxExec(
        val command: List<Template>, val cwd: Template?, val env: Map<String, Template>, val timeoutSec: Int?,
        val output: ExecOutput, override val bindAs: String?,
    ) : Action { override val type get() = ActionType.SANDBOX_EXEC }

    data class OpenFile(val path: Template, val line: Int?, val column: Int?, val preview: Boolean, override val bindAs: String?) : Action {
        override val type get() = ActionType.OPEN_FILE
    }

    data class OpenUrl(val url: Template, override val bindAs: String?) : Action {
        override val type get() = ActionType.OPEN_URL
    }

    data class ApplyEdit(val edits: EditSpec, override val bindAs: String?) : Action {
        override val type get() = ActionType.APPLY_EDIT
    }

    data class InsertSnippet(val source: SnippetSource, override val bindAs: String?) : Action {
        override val type get() = ActionType.INSERT_SNIPPET
    }

    data class SetConfig(val key: String, val value: JsonTemplate, val target: ConfigTarget, override val bindAs: String?) : Action {
        override val type get() = ActionType.SET_CONFIG
    }

    /** [values] is the cycle list, default `[true, false]`. */
    data class ToggleConfig(val key: String, val values: List<JsonElement>, val target: ConfigTarget, override val bindAs: String?) : Action {
        override val type get() = ActionType.TOGGLE_CONFIG
    }

    data class LspRequest(
        val method: String, val params: JsonTemplate?, val language: String?, val then: LspThen, override val bindAs: String?,
    ) : Action { override val type get() = ActionType.LSP_REQUEST }

    data class ExecuteCommand(val command: String, val args: JsonTemplate?, override val bindAs: String?) : Action {
        override val type get() = ActionType.EXECUTE_COMMAND
    }

    data class ShowQuickPick(
        val id: String, val items: QuickPickSource, val placeHolder: Template?, val canPickMany: Boolean, override val bindAs: String?,
    ) : Action { override val type get() = ActionType.SHOW_QUICK_PICK }

    data class ShowInputBox(
        val id: String, val prompt: Template?, val value: Template?, val placeHolder: Template?,
        val validate: Regex?, val password: Boolean, override val bindAs: String?,
    ) : Action {
        override val type get() = ActionType.SHOW_INPUT_BOX
        // Regex has identity equality; compare by source so decoded manifests compare structurally.
        override fun equals(other: Any?): Boolean = other is ShowInputBox && other.id == id && other.prompt == prompt &&
            other.value == value && other.placeHolder == placeHolder && other.validate?.pattern == validate?.pattern &&
            other.password == password && other.bindAs == bindAs
        override fun hashCode(): Int = id.hashCode() * 31 + (validate?.pattern?.hashCode() ?: 0)
    }

    data class ShowMessage(val text: Template, val severity: MessageSeverity, val actions: List<MessageAction>, override val bindAs: String?) : Action {
        override val type get() = ActionType.SHOW_MESSAGE
    }

    data class RevealStage(val stage: String, val view: String?, val focus: Boolean, override val bindAs: String?) : Action {
        override val type get() = ActionType.REVEAL_STAGE
    }

    data class Sequence(val steps: List<Action>, val continueOnError: Boolean, override val bindAs: String?) : Action {
        override val type get() = ActionType.SEQUENCE
    }
}

enum class ActionType(val wire: String) {
    RUN_IN_TERMINAL("runInTerminal"), RUN_TASK("runTask"), SANDBOX_EXEC("sandboxExec"), OPEN_FILE("openFile"),
    OPEN_URL("openUrl"), APPLY_EDIT("applyEdit"), INSERT_SNIPPET("insertSnippet"), SET_CONFIG("setConfig"),
    TOGGLE_CONFIG("toggleConfig"), LSP_REQUEST("lspRequest"), EXECUTE_COMMAND("executeCommand"),
    SHOW_QUICK_PICK("showQuickPick"), SHOW_INPUT_BOX("showInputBox"), SHOW_MESSAGE("showMessage"),
    REVEAL_STAGE("revealStage"), SEQUENCE("sequence");

    companion object {
        fun parse(wire: String): ActionType? = entries.firstOrNull { it.wire == wire }
    }
}

enum class ExecOutput(val wire: String) { TERMINAL("terminal"), SILENT("silent"), CAPTURE("capture") }

enum class LspThen(val wire: String) { SHOW_LOCATIONS("showLocations"), APPLY_WORKSPACE_EDIT("applyWorkspaceEdit"), SHOW_MESSAGE("showMessage"), NONE("none") }

enum class MessageSeverity(val wire: String) { INFO("info"), WARNING("warning"), ERROR("error") }

sealed interface TaskRef {
    data class Label(val label: Template) : TaskRef
    /** A `{type, ...}` task definition, matched against contributed `taskDefinitions`. */
    data class Definition(val definition: JsonTemplate) : TaskRef
}

sealed interface EditSpec {
    data class TextEdits(val edits: List<TextEdit>) : EditSpec
    /** An LSP `WorkspaceEdit`; its URIs are checked against the workspace before applying. */
    data class Workspace(val edit: JsonTemplate) : EditSpec
}

/** [path] null = the active editor's file. Positions are 0-based (LSP). */
data class TextEdit(val path: Template?, val range: TextRange, val text: Template)

data class TextPosition(val line: Int, val character: Int)
data class TextRange(val start: TextPosition, val end: TextPosition)

sealed interface SnippetSource {
    data class Body(val body: Template) : SnippetSource
    data class Named(val name: String, val language: String?) : SnippetSource
}

sealed interface QuickPickSource {
    data class Items(val items: List<QuickPickItem>) : QuickPickSource
    /** Newline text or a JSON array, usually `${result:x}` from a capture. */
    data class From(val source: Template) : QuickPickSource
}

/** [value] null = the label is the value. */
data class QuickPickItem(val label: Template, val description: Template?, val value: JsonTemplate?)

data class MessageAction(val title: Template, val action: Action?)

/** `easyide.inputs` entries resolving `${input:id}` (VS Code `inputs` shape). */
sealed interface InputSpec {
    val id: String

    data class PromptString(override val id: String, val description: String?, val default: String?, val password: Boolean) : InputSpec
    data class PickString(override val id: String, val description: String?, val options: List<PickOption>, val default: String?) : InputSpec
    data class Command(override val id: String, val command: String, val args: JsonElement?) : InputSpec
}

data class PickOption(val label: String, val value: String)
