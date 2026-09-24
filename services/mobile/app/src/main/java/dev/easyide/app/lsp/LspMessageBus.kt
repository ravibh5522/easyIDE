package dev.easyide.app.lsp

import android.util.Log
import dev.easyide.lsp.session.LspLogSink
import dev.easyide.lsp.session.LspUi
import dev.easyide.lsp.session.MessageType
import dev.easyide.lsp.session.ServerKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A `window/showMessage` the workspace shows as a snackbar. */
data class ServerMessage(val key: ServerKey, val type: MessageType, val text: String)

/**
 * A `window/showMessageRequest` waiting for the user. [answer] completes the server's
 * request; the workspace of [key]'s project shows it as a dialog.
 */
class ServerQuestion internal constructor(
    val key: ServerKey,
    val type: MessageType,
    val text: String,
    val actions: List<String>,
    private val reply: CompletableDeferred<String?>,
) {
    /** The chosen action title, or null for dismissed. Later calls are ignored. */
    fun answer(action: String?) {
        reply.complete(action)
    }
}

/**
 * The app's [LspUi]: server-initiated UI crosses from session dispatchers to whichever
 * workspace shows that project. Messages are a hot stream (a snackbar nobody sees is
 * dropped, the session log keeps it); questions stay pending until answered, so one asked
 * while the workspace is recomposing is not lost.
 */
class LspMessageBus : LspUi {

    private val messageFlow = MutableSharedFlow<ServerMessage>(
        extraBufferCapacity = MESSAGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val questionFlow = MutableStateFlow<List<ServerQuestion>>(emptyList())

    val messages: SharedFlow<ServerMessage> = messageFlow.asSharedFlow()
    val questions: StateFlow<List<ServerQuestion>> = questionFlow.asStateFlow()

    override fun showMessage(key: ServerKey, type: MessageType, message: String) {
        messageFlow.tryEmit(ServerMessage(key, type, message))
    }

    override suspend fun ask(key: ServerKey, type: MessageType, message: String, actions: List<String>): String? {
        val reply = CompletableDeferred<String?>()
        val question = ServerQuestion(key, type, message, actions, reply)
        questionFlow.update { it + question }
        return try {
            reply.await()
        } finally {
            // Also on cancellation (the server went away): the dialog must not outlive it.
            questionFlow.update { it - question }
        }
    }

    private companion object {
        /** Snackbars are shown one at a time; a burst beyond this is not worth queueing. */
        const val MESSAGE_BUFFER = 8
    }
}

/**
 * Mirrors session log lines to logcat so `adb logcat -s EasyIdeLsp` follows servers during
 * development. The session's own ring (what the log viewer shows) is the source of truth.
 */
object LogcatLspSink : LspLogSink {
    private const val TAG = "EasyIdeLsp"

    override fun append(key: ServerKey, line: String) {
        Log.d(TAG, "$key $line")
    }
}
