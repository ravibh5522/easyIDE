package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.easyide.app.R
import dev.easyide.app.lsp.servers.LanguageServerRows
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.Tone

/** Add or edit one custom `lsp.servers` entry; [onSave] says whether the layer accepted it. */
@Composable
internal fun ServerFormDialog(key: String?, initial: Pair<String, String>, onSave: (String, String, String) -> Boolean, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(key.orEmpty()) }
    var languages by rememberSaveable { mutableStateOf(initial.first) }
    var command by rememberSaveable { mutableStateOf(initial.second) }
    var invalid by remember { mutableStateOf(false) }
    val codeInput = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    KitDialog(
        title = stringResource(if (key == null) R.string.lsp_add else R.string.lsp_edit),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.action_save)) { if (onSave(name, languages, command)) onDismiss() else invalid = true },
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            KitField(
                name, { name = it; invalid = false }, label = stringResource(R.string.lsp_form_key),
                error = if (invalid && !LanguageServerRows.isValidCustomKey(name)) stringResource(R.string.lsp_form_key_invalid) else null,
                mono = true, keyboard = codeInput,
            )
            KitField(languages, { languages = it; invalid = false }, label = stringResource(R.string.lsp_form_languages), mono = true, keyboard = codeInput)
            KitField(command, { command = it; invalid = false }, label = stringResource(R.string.lsp_form_command), singleLine = false, mono = true, keyboard = codeInput)
            BodyText(stringResource(R.string.lsp_form_hint), tone = Tone.Neutral)
            if (invalid) BodyText(stringResource(R.string.lsp_form_invalid), tone = Tone.Danger)
        }
    }
}
