package dev.easyide.app.extensions.host

import dev.easyide.extensions.action.InputBoxRequest
import dev.easyide.extensions.action.MessageRequest
import dev.easyide.extensions.action.QuickPickRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/** One prompt an action is waiting on; the screen renders it and completes [result]. */
sealed class UiPrompt<T> {
    val result: CompletableDeferred<T> = CompletableDeferred()

    class QuickPick(val request: QuickPickRequest) : UiPrompt<List<JsonElement>?>()
    class InputBox(val request: InputBoxRequest) : UiPrompt<String?>()
    class Message(val request: MessageRequest) : UiPrompt<String?>()

    /** The full-URL confirm sheet before anything opens a link (R-SEC-11). */
    class ConfirmUrl(val url: String) : UiPrompt<Boolean>()
}

/**
 * Owns the prompts extension actions raise (quick pick, input box, message, URL
 * confirm) and the transient notices (failed commands, plain messages). Actions suspend
 * in [show] until the workspace's prompt host answers; prompts queue, so two commands
 * prompting at once are asked one after the other. Cancelling the waiting coroutine
 * withdraws its prompt.
 */
class ExtensionUiHost {

    private val queue = MutableStateFlow<List<UiPrompt<*>>>(emptyList())
    val prompts: StateFlow<List<UiPrompt<*>>> = queue.asStateFlow()

    private val noticeFlow = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-line messages for the workspace snackbar. */
    val notices: SharedFlow<String> = noticeFlow.asSharedFlow()

    suspend fun <T> show(prompt: UiPrompt<T>): T {
        queue.update { it + prompt }
        try {
            return prompt.result.await()
        } finally {
            queue.update { list -> list.filterNot { it === prompt } }
        }
    }

    /** The screen's answer; the first answer wins and the prompt leaves the queue. */
    fun <T> answer(prompt: UiPrompt<T>, value: T) {
        prompt.result.complete(value)
        queue.update { list -> list.filterNot { it === prompt } }
    }

    fun notify(text: String) {
        noticeFlow.tryEmit(text)
    }

    private companion object { const val NOTICE_BUFFER = 8 }
}
