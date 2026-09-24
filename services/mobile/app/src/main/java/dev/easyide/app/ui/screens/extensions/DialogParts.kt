package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.kitMono
import androidx.compose.ui.res.stringResource

/** A label inside a dialog body: one step above the text, never a coloured block. */
@Composable
internal fun DialogHeading(text: String) {
    BasicText(
        text,
        Modifier.padding(top = Kit.space.m, bottom = Kit.space.xs).semantics { heading() },
        style = Kit.type.labelLarge.copy(color = Kit.colors.plainText),
    )
}

@Composable
internal fun DialogText(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (muted) Kit.colors.textMuted else Kit.colors.plainText
    BasicText(text, modifier.padding(top = Kit.space.xs), style = Kit.type.bodyMedium.copy(color = color))
}

/** Ids, versions, hashes and commands are code-like strings: mono, verbatim. */
@Composable
internal fun DialogMono(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(top = Kit.space.xs), style = Kit.type.bodySmall.kitMono().copy(color = Kit.colors.plainText))
}

/** Refusals and failures: the reasons verbatim, one action. */
@Composable
internal fun ProblemDialog(title: String, problems: List<String>, onDismiss: () -> Unit) {
    KitDialog(title, onDismiss, confirm = KitAction(stringResource(R.string.ext_prompt_close), onDismiss)) {
        problems.forEach { DialogMono(it) }
    }
}
