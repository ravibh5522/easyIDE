package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.extensions.host.ExtensionUiHost
import dev.easyide.app.extensions.host.UiPrompt
import dev.easyide.app.ui.commands.fuzzyFilter
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.kit.kitMono
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.extensions.action.MessageSeverity

/**
 * Renders the prompt extension actions are waiting on (the head of [ui]'s queue): quick
 * pick, input box, message with actions, and the full-URL confirm sheet. Dismissing any
 * of them answers null/false, which the runner treats as a silent cancel.
 */
@Composable
fun ExtensionPromptHost(ui: ExtensionUiHost) {
    val prompts by ui.prompts.collectAsStateWithLifecycle()
    when (val p = prompts.firstOrNull()) {
        null -> Unit
        is UiPrompt.QuickPick -> QuickPickDialog(p, ui)
        is UiPrompt.InputBox -> InputBoxDialog(p, ui)
        is UiPrompt.Message -> MessageDialog(p, ui)
        is UiPrompt.ConfirmUrl -> ConfirmUrlDialog(p, ui)
    }
}

@Composable
private fun QuickPickDialog(p: UiPrompt.QuickPick, ui: ExtensionUiHost) {
    val r = p.request
    var query by remember(p) { mutableStateOf("") }
    var picked by remember(p) { mutableStateOf(setOf<Int>()) }
    val indexed = r.items.withIndex().toList()
    val matches = fuzzyFilter(query, indexed) { it.value.label }
    KitDialog(
        title = r.title,
        onDismiss = { ui.answer(p, null) },
        confirm = if (r.canPickMany) KitAction(stringResource(R.string.ext_prompt_ok)) { ui.answer(p, picked.sorted().map { r.items[it].value }) } else null,
        dismiss = KitAction(stringResource(R.string.ext_prompt_cancel)) { ui.answer(p, null) },
    ) {
        KitField(query, { query = it }, hint = r.placeHolder)
        if (matches.isEmpty()) DialogText(stringResource(R.string.palette_empty), Modifier.padding(top = Kit.space.s), muted = true)
        // A chooser inside a dialog is a list (U-CMP-07); the bound keeps the action row on screen.
        LazyColumn(modifier = Modifier.padding(top = Kit.space.s).heightIn(max = LIST_MAX_DP.dp)) {
            items(matches, key = { it.index }) { item ->
                KitRow(
                    title = item.value.label,
                    subtitle = item.value.description,
                    leading = if (r.canPickMany) {
                        { KitToggle(item.index in picked, null, kind = ToggleKind.Check) }
                    } else null,
                    onClick = {
                        if (r.canPickMany) picked = if (item.index in picked) picked - item.index else picked + item.index
                        else ui.answer(p, listOf(item.value.value))
                    },
                )
            }
        }
    }
}

@Composable
private fun InputBoxDialog(p: UiPrompt.InputBox, ui: ExtensionUiHost) {
    val r = p.request
    var value by remember(p) { mutableStateOf(r.value.orEmpty()) }
    val valid = r.validate?.matches(value) ?: true
    KitDialog(
        title = r.title,
        onDismiss = { ui.answer(p, null) },
        confirm = if (valid) KitAction(stringResource(R.string.ext_prompt_ok)) { ui.answer(p, value) } else null,
        dismiss = KitAction(stringResource(R.string.ext_prompt_cancel)) { ui.answer(p, null) },
    ) {
        r.prompt?.let { DialogText(it, Modifier.padding(bottom = Kit.space.s)) }
        KitField(
            value = value,
            onValueChange = { value = it },
            hint = r.placeHolder,
            error = if (valid) null else stringResource(R.string.ext_prompt_invalid),
            keyboard = if (r.password) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            visualTransformation = if (r.password) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
private fun MessageDialog(p: UiPrompt.Message, ui: ExtensionUiHost) {
    val r = p.request
    KitDialog(
        title = stringResource(when (r.severity) {
            MessageSeverity.INFO -> R.string.ext_message_info
            MessageSeverity.WARNING -> R.string.ext_message_warning
            MessageSeverity.ERROR -> R.string.ext_message_error
        }, r.owner.value),
        onDismiss = { ui.answer(p, null) },
        // The pack's first action is the dialog's action; the others are equal alternatives below the text.
        confirm = r.actions.firstOrNull()?.let { first -> KitAction(first) { ui.answer(p, first) } },
        dismiss = KitAction(stringResource(R.string.ext_prompt_close)) { ui.answer(p, null) },
    ) {
        DialogText(r.text)
        r.actions.drop(1).forEach { title ->
            KitButton(title, { ui.answer(p, title) }, Modifier.padding(top = Kit.space.s), KitButtonStyle.Secondary)
        }
    }
}

/** R-SEC-11: the whole URL is shown before anything opens it. */
@Composable
private fun ConfirmUrlDialog(p: UiPrompt.ConfirmUrl, ui: ExtensionUiHost) {
    KitDialog(
        title = stringResource(R.string.ext_open_url_title),
        onDismiss = { ui.answer(p, false) },
        confirm = KitAction(stringResource(R.string.ext_open_url_confirm)) { ui.answer(p, true) },
        dismiss = KitAction(stringResource(R.string.ext_prompt_cancel)) { ui.answer(p, false) },
    ) {
        BasicText(p.url, style = Kit.type.bodyMedium.kitMono().copy(color = Kit.colors.plainText))
    }
}

private const val LIST_MAX_DP = 360
