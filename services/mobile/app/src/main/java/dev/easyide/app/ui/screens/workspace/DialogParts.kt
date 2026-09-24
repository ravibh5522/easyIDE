package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.easyide.app.ui.kit.Kit

/** Names, paths and branches: no capital, no autocorrect (U-INT-06). */
internal val NAME_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done)

/** Remote and host addresses. */
internal val URL_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)

/** A label inside a dialog body: one step above the text, never a coloured block. */
@Composable
internal fun DialogHeading(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier.padding(top = Kit.space.m, bottom = Kit.space.xs).semantics { heading() },
        style = Kit.type.labelLarge.copy(color = Kit.colors.plainText),
    )
}

@Composable
internal fun DialogText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val color = if (muted) Kit.colors.textMuted else Kit.colors.plainText
    BasicText(text, modifier, style = Kit.type.bodyMedium.copy(color = color))
}
