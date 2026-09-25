package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.easyide.app.R
import dev.easyide.app.ui.commands.KeyFocus
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow

/** Key text is validated with [KeyNames]; the command may be typed or picked from the known ids. */
@Composable
internal fun AddBindingDialog(commands: List<String>, onAdd: (String, String, String?) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var focus by rememberSaveable { mutableStateOf(KeyFocus.ANYWHERE) }
    var submitted by remember { mutableStateOf(false) }
    val keyValid = KeyNames.parseSequence(key) != null
    val matches = remember(command, commands) {
        if (command.isBlank()) emptyList() else commands.filter { it.contains(command, ignoreCase = true) && it != command }.take(MAX_SUGGESTIONS)
    }
    val focusLabels = KeyFocus.entries.associateWith { stringResource(focusLabel(it)) }
    val codeInput = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    KitDialog(
        title = stringResource(R.string.keys_add),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.action_save)) {
            if (keyValid && command.isNotBlank()) { onAdd(key, command, focus.whenText); onDismiss() } else submitted = true
        },
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            KitField(
                key, { key = it }, label = stringResource(R.string.keys_form_key), hint = stringResource(R.string.keys_form_key_hint),
                error = if ((key.isNotBlank() || submitted) && !keyValid) stringResource(R.string.keys_form_key_invalid) else null,
                mono = true, keyboard = codeInput,
            )
            KitField(
                command, { command = it }, label = stringResource(R.string.keys_form_command),
                error = if (submitted && command.isBlank()) stringResource(R.string.keys_form_command_required) else null,
                mono = true, keyboard = codeInput,
            )
            matches.forEach { id -> KitRow(id, mono = true, onClick = { command = id }) }
            KitChoice(KeyFocus.entries, focus, { focusLabels.getValue(it) }, { focus = it })
        }
    }
}

private fun focusLabel(f: KeyFocus): Int = when (f) {
    KeyFocus.ANYWHERE -> R.string.keys_focus_anywhere
    KeyFocus.OUTSIDE_TERMINAL -> R.string.keys_focus_outside_terminal
    KeyFocus.TERMINAL_ONLY -> R.string.keys_focus_terminal
}

private const val MAX_SUGGESTIONS = 6
