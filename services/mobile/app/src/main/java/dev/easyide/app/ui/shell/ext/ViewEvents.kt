package dev.easyide.app.ui.shell.ext

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.view.ResolvedAction
import dev.easyide.extensions.view.ResolvedEffect
import dev.easyide.extensions.view.ResolvedTarget
import dev.easyide.extensions.view.ViewData
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** How a command or action a view called ended. */
sealed interface CallResult {
    data class Value(val value: JsonElement) : CallResult

    /** Nothing to write back: a document opened, or a local edit was all there was. */
    data object Done : CallResult

    /** The user dismissed a prompt the command showed; silent by design. */
    data object Cancelled : CallResult
    data class Failed(val message: String) : CallResult
}

/** What firing a view event needs from the app: run a command or an inline action as a pack, and open a document. */
interface ViewCalls {
    suspend fun command(owner: String, id: String, args: JsonElement?): CallResult
    suspend fun inline(owner: String, title: String, action: Action, args: JsonElement?): CallResult
    fun open(uri: String, group: OpenGroup): Boolean
}

/**
 * Runs the events of extension views (extension-ui.md sections 4.3 and 4.4): the `before` effects, the command,
 * the result written back where `as` says, then the `after` effects whatever happened. A failure is told to the user
 * once, in the pack's words, and logged; it never leaves the view half edited, because `after` still runs.
 * An event already running is not started twice (a double tap runs a command once).
 */
class ViewEvents(private val hub: ViewDataHub, private val calls: ViewCalls) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * [key] names the view's data, [initial] is its state, [title] names the pack's control in messages. [onFailed] tells the
     * user what did not work, in the shell's own words (a toast), with the reason the command or the result gave.
     */
    suspend fun run(owner: String, key: String, initial: JsonObject, title: String, action: ResolvedAction, onFailed: (title: String, reason: String) -> Unit) {
        val flight = "$owner|$key|${action.target}"
        if (!running.add(flight)) return
        try {
            action.before.forEach { edit(key, initial, it) }
            val outcome = call(owner, title, action.target)
            val into = action.into
            val exited = (outcome as? CallResult.Value)?.let { ViewData.execFailure(it.value) }
            when {
                outcome is CallResult.Failed -> onFailed(title, outcome.message)
                exited != null -> onFailed(title, exited)
                outcome is CallResult.Value && into != null -> {
                    val written = hub.write(key, initial) { ViewData.write(it, into, outcome.value) }
                    if (written is ViewData.Written.Rejected) onFailed(title, written.reason)
                }
                else -> Unit
            }
        } finally {
            action.after.forEach { edit(key, initial, it) }
            running.remove(flight)
        }
    }

    private fun edit(key: String, initial: JsonObject, effect: ResolvedEffect) = hub.edit(key, initial) { ViewData.apply(it, effect) }

    private suspend fun call(owner: String, title: String, target: ResolvedTarget): CallResult = when (target) {
        is ResolvedTarget.Command -> calls.command(owner, target.id, target.args)
        is ResolvedTarget.Inline -> calls.inline(owner, title, target.action, target.args)
        is ResolvedTarget.Open -> if (calls.open(target.uri, target.group)) CallResult.Done else CallResult.Failed("cannot open ${target.uri}")
        ResolvedTarget.Local -> CallResult.Done
    }
}
