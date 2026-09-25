package dev.easyide.app.extensions.host

import dev.easyide.app.ui.shell.ext.CallResult
import dev.easyide.app.ui.shell.ext.ViewCalls
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.action.StepResult
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.serialization.json.JsonElement

/**
 * What a view event calls, over the extension runtime: a command through the action engine, an inline action as the pack
 * with the pack's own grants, a document through the shell that is showing. A failure is logged for the Extension Log and
 * returned for the view's toast; a dismissed prompt is silent.
 */
class ExtViewCalls(
    private val runtime: ExtensionsRuntime,
    private val host: AppHostPort,
    private val log: ExtensionLog,
) : ViewCalls {

    override suspend fun command(owner: String, id: String, args: JsonElement?): CallResult = when (val r = runtime.run(id, args)) {
        is ActionOutcome.Done -> CallResult.Value(r.value)
        ActionOutcome.Cancelled -> CallResult.Cancelled
        is ActionOutcome.Failed -> CallResult.Failed(r.message)
    }

    override suspend fun inline(owner: String, title: String, action: Action, args: JsonElement?): CallResult {
        val id = ExtensionId.parse(owner) ?: return CallResult.Failed("unknown extension $owner")
        return when (val r = runtime.runInline(id, title, action, args)) {
            is StepResult.Value -> CallResult.Value(r.value)
            StepResult.Cancelled -> CallResult.Cancelled
            is StepResult.Failed -> {
                log.append(LogEntry(id, LogLevel.ERROR, "$title failed: ${r.error.code} ${r.message}"))
                CallResult.Failed(r.message)
            }
        }
    }

    override fun open(uri: String, group: OpenGroup): Boolean = host.documents?.open(uri, group, true) ?: false
}
