package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.Tone

/** Code-like text (branch, path, size, time) in the chrome monospace, muted unless it is the row's subject. */
@Composable
internal fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    tone: Tone? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val colors = Kit.colors
    val color = tone?.content(colors) ?: if (muted) colors.textMuted else colors.plainText
    BasicText(text, modifier, style = Kit.text.monoSmall.copy(color = color), maxLines = 1, overflow = overflow)
}

/** Prose inside a dialog or page: body size, plain or muted. */
@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = Kit.colors
    BasicText(text, modifier, style = Kit.text.body.copy(color = if (muted) colors.textMuted else colors.plainText))
}

private val DOT = 8.dp

/**
 * The state dot of a Running row. Colour alone never carries the state (U-COL-05): the row also
 * says it in words, so the dot is hidden from screen readers.
 */
@Composable
internal fun StateDot(tone: Tone, modifier: Modifier = Modifier) {
    Box(modifier.size(DOT).background(tone.content(Kit.colors)).clearAndSetSemantics { })
}
