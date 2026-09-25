package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.Tone
import kotlinx.coroutines.delay

/**
 * The shell's short messages, one at a time (identity.md 10). Pure value: [show] queues a text
 * behind the one on screen, [dismissed] moves to the next. A repeat of a text that is already on
 * screen or waiting adds nothing, so a page that reports the same failure twice shows it once, and
 * the queue is capped so a runaway reporter cannot pile up minutes of messages.
 */
data class ToastQueue(val current: String? = null, val waiting: List<String> = emptyList()) {

    fun show(text: String): ToastQueue = when {
        text.isBlank() -> this
        current == null -> copy(current = text)
        text == current || text in waiting -> this
        else -> copy(waiting = (waiting + text).takeLast(ShellTokens.TOAST_QUEUE_MAX))
    }

    fun dismissed(): ToastQueue = ToastQueue(waiting.firstOrNull(), waiting.drop(1))
}

/**
 * Draws the current toast as a [KitBanner] (which announces itself politely to screen readers) and
 * dismisses it after [ShellTokens.TOAST_MS]. Keyed by the text, so the next message restarts the timer.
 */
@Composable
fun ToastHost(current: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (current == null) return
    LaunchedEffect(current) {
        delay(ShellTokens.TOAST_MS)
        onDismiss()
    }
    Box(modifier.padding(Kit.space.s)) { KitBanner(current, tone = Tone.Info, onDismiss = onDismiss) }
}
