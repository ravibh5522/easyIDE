package dev.easyide.extensions.action

import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extensions.whenclause.ContextSnapshot
import kotlinx.serialization.json.JsonElement

/** sdk-reference error codes (shared with the WASM host API). */
enum class ActionError(val code: String) {
    CAPABILITY("E_CAPABILITY"), ARGS("E_ARGS"), NOT_FOUND("E_NOT_FOUND"), TIMEOUT("E_TIMEOUT"),
    LIMIT("E_LIMIT"), UNAVAILABLE("E_UNAVAILABLE"), INTERNAL("E_INTERNAL"),
}

/** How one command invocation ended. A cancelled prompt is silent (no snackbar, no log). */
sealed interface ActionOutcome {
    data class Done(val value: JsonElement) : ActionOutcome
    data object Cancelled : ActionOutcome

    /** [stepPath] like `sequence.steps[2].sandboxExec`; [stderrTail] for process failures. */
    data class Failed(val stepPath: String, val error: ActionError, val message: String, val stderrTail: String? = null) : ActionOutcome
}

/** How an extension command is handled: declaratively (L1 action) or by its WASM module (L2). */
sealed interface CommandHandler {
    data class Declarative(val action: Action) : CommandHandler
    data object Logic : CommandHandler
}

/** An extension-owned command as the runner needs it; [granted] = declared AND approved. */
data class CommandBinding(
    val owner: ExtensionId,
    val commandId: String,
    val title: String,
    val handler: CommandHandler,
    val inputs: Map<String, InputSpec>,
    val granted: CapabilitySet,
    val ownSettings: Set<String>,
    val extensionPath: String,
)

/** Resolves a command id to its extension binding; null for built-in or unknown commands. */
fun interface CommandCatalog {
    fun binding(commandId: String): CommandBinding?
}

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

data class LogEntry(val extensionId: ExtensionId?, val level: LogLevel, val message: String)

/** The Extension Log ring (ECO-31) lives in `:app`; entries are already redacted (R-SEC-17). */
fun interface ExtensionLog {
    fun append(entry: LogEntry)
}

/**
 * State of one invocation (extension-runtime.md sec 8.2). [snapshot] and [editor] are frozen
 * at invocation, so `${file}` is the file the user tapped Run on even if a prompt lets them
 * switch tabs. [results] and [inputs] fill as steps run.
 */
class ActionContext internal constructor(
    val binding: CommandBinding,
    val args: JsonElement?,
    val snapshot: ContextSnapshot,
    val editor: EditorState?,
    val workspace: WorkspaceState,
    internal val depth: Int,
) {
    val owner: ExtensionId get() = binding.owner
    val granted: CapabilitySet get() = binding.granted
    val results: MutableMap<String, JsonElement> = HashMap()
    val inputs: MutableMap<String, JsonElement> = HashMap()

    /** Values from password prompts; redacted from every log line (R-SEC-17). */
    internal val secrets: MutableSet<String> = HashSet()

    /** Settings coordinates frozen with the context: `${config:}` reads for this scope and language. */
    val query: SettingsQuery get() = SettingsQuery(editor?.languageId ?: snapshot.languageId, workspace.envId, snapshot.runtime.projectId)

    fun redact(text: String): String = secrets.filter { it.isNotEmpty() }.fold(text) { acc, s -> acc.replace(s, REDACTED) }

    private companion object { const val REDACTED = "***" }
}

/** POSIX `sh` single-quoting: the result is exactly one word whatever [s] contains. */
object ShellQuote {
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** Where a substituted value lands decides its quoting (extension-runtime.md sec 8.4), never the author. */
enum class QuoteMode { PLAIN, SHELL, ARGV_ELEMENT }
