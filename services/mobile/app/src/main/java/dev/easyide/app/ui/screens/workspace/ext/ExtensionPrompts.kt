package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.extensions.host.ExtensionUiHost
import dev.easyide.app.extensions.host.UiPrompt
import dev.easyide.app.ui.commands.fuzzyFilter
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
    AlertDialog(
        onDismissRequest = { ui.answer(p, null) },
        title = { Text(r.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = r.placeHolder?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isEmpty()) Text(stringResource(R.string.palette_empty), modifier = Modifier.padding(top = Spacing.s))
                LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_DP.dp)) {
                    items(matches, key = { it.index }) { item ->
                        ListItem(
                            headlineContent = { Text(item.value.label) },
                            supportingContent = item.value.description?.let { { Text(it) } },
                            leadingContent = if (r.canPickMany) {
                                { Checkbox(checked = item.index in picked, onCheckedChange = null) }
                            } else null,
                            modifier = Modifier.clickable {
                                if (r.canPickMany) picked = if (item.index in picked) picked - item.index else picked + item.index
                                else ui.answer(p, listOf(item.value.value))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (r.canPickMany) {
                TextButton(onClick = { ui.answer(p, picked.sorted().map { r.items[it].value }) }) { Text(stringResource(R.string.ext_prompt_ok)) }
            }
        },
        dismissButton = { TextButton(onClick = { ui.answer(p, null) }) { Text(stringResource(R.string.ext_prompt_cancel)) } },
    )
}

@Composable
private fun InputBoxDialog(p: UiPrompt.InputBox, ui: ExtensionUiHost) {
    val r = p.request
    var value by remember(p) { mutableStateOf(r.value.orEmpty()) }
    val valid = r.validate?.matches(value) ?: true
    AlertDialog(
        onDismissRequest = { ui.answer(p, null) },
        title = { Text(r.title) },
        text = {
            Column {
                r.prompt?.let { Text(it, modifier = Modifier.padding(bottom = Spacing.s)) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid,
                    placeholder = r.placeHolder?.let { { Text(it) } },
                    visualTransformation = if (r.password) PasswordVisualTransformation() else VisualTransformation.None,
                    supportingText = if (!valid) { { Text(stringResource(R.string.ext_prompt_invalid)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { ui.answer(p, value) }, enabled = valid) { Text(stringResource(R.string.ext_prompt_ok)) } },
        dismissButton = { TextButton(onClick = { ui.answer(p, null) }) { Text(stringResource(R.string.ext_prompt_cancel)) } },
    )
}

@Composable
private fun MessageDialog(p: UiPrompt.Message, ui: ExtensionUiHost) {
    val r = p.request
    AlertDialog(
        onDismissRequest = { ui.answer(p, null) },
        title = {
            Text(stringResource(when (r.severity) {
                MessageSeverity.INFO -> R.string.ext_message_info
                MessageSeverity.WARNING -> R.string.ext_message_warning
                MessageSeverity.ERROR -> R.string.ext_message_error
            }, r.owner.value))
        },
        text = { Text(r.text) },
        confirmButton = {
            Column {
                r.actions.forEach { title -> TextButton(onClick = { ui.answer(p, title) }) { Text(title) } }
            }
        },
        dismissButton = { TextButton(onClick = { ui.answer(p, null) }) { Text(stringResource(R.string.ext_prompt_close)) } },
    )
}

/** R-SEC-11: the whole URL is shown before anything opens it. */
@Composable
private fun ConfirmUrlDialog(p: UiPrompt.ConfirmUrl, ui: ExtensionUiHost) {
    AlertDialog(
        onDismissRequest = { ui.answer(p, false) },
        title = { Text(stringResource(R.string.ext_open_url_title)) },
        text = { Text(p.url, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { ui.answer(p, true) }) { Text(stringResource(R.string.ext_open_url_confirm)) } },
        dismissButton = { TextButton(onClick = { ui.answer(p, false) }) { Text(stringResource(R.string.ext_prompt_cancel)) } },
    )
}

private const val LIST_MAX_DP = 360
