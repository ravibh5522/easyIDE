package dev.easyide.app.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitMono

/** Prose in a flow or a dialog: body size, plain or muted. Paragraphs are never set in mono. */
@Composable
fun ProseText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = Kit.colors
    BasicText(text, modifier, style = Kit.type.bodyMedium.copy(color = if (muted) colors.textMuted else colors.plainText))
}

/** Code-like or measured text (paths, versions, sizes, log lines) in the chrome monospace; [tone] wins over [muted]. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    tone: Tone? = null,
    maxLines: Int = 1,
) {
    val colors = Kit.colors
    val color = tone?.content(colors) ?: if (muted) colors.textMuted else colors.plainText
    BasicText(text, modifier, style = Kit.type.bodySmall.kitMono().copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
