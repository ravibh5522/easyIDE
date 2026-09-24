package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.lsp.servers.LanguageServerRows
import dev.easyide.app.lsp.servers.RejectReason
import dev.easyide.app.lsp.servers.ServerIssue
import dev.easyide.app.lsp.servers.ServerRow
import dev.easyide.app.ui.theme.Spacing

/** The add/edit form's subject: a new custom server, or the existing entry [key]. */
private data class ServerForm(val key: String?)

/**
 * Language servers (customization.md sec 12): every resolved server for the selected
 * layer tab with its source, languages, command and on/off switch; custom servers can be
 * added, edited and removed; `lsp.servers` entries that are ignored are listed with why.
 * Every edit writes the tab's layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageServersDialog(state: LanguageServersState?, layerName: String, controller: LanguageServersController, onClose: () -> Unit) {
    var form by remember { mutableStateOf<ServerForm?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.lsp_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                    },
                    actions = {
                        IconButton(onClick = { form = ServerForm(null) }, enabled = state != null) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lsp_add))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        stringResource(R.string.lsp_screen_layer, layerName) + " " +
                            (state?.environmentId?.let { stringResource(R.string.lsp_screen_env, it) } ?: stringResource(R.string.lsp_screen_no_env)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                val issues = state?.view?.issues.orEmpty()
                if (issues.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.lsp_issues)) }
                    items(issues, key = { "i:" + it.key }) { IssueRow(it) }
                    item { HorizontalDivider() }
                }
                val rows = state?.view?.rows.orEmpty()
                if (rows.isEmpty()) {
                    item { Text(stringResource(R.string.lsp_none), modifier = Modifier.padding(Spacing.l)) }
                }
                items(rows, key = { it.key }) { row ->
                    ServerListRow(
                        row = row,
                        onEnabled = { controller.setEnabled(row.key, it) },
                        onEdit = { form = ServerForm(row.key) },
                        onRemove = { controller.remove(row.key) },
                    )
                }
            }
        }
    }
    form?.let { f ->
        ServerFormDialog(
            key = f.key,
            initial = LanguageServerRows.formText(f.key?.let { state?.layerValue?.get(it) }),
            onSave = { key, languages, command -> controller.saveCustom(key, languages, command, f.key) },
            onDismiss = { form = null },
        )
    }
}

@Composable
private fun ServerListRow(row: ServerRow, onEnabled: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(row.key) },
        supportingContent = {
            Column {
                Text(row.extensionId?.let { stringResource(R.string.lsp_source_pack, it) } ?: stringResource(R.string.lsp_source_custom))
                Text(stringResource(R.string.lsp_languages, row.languages.sorted().joinToString()))
                Text(row.command.joinToString(" "), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (row.customized && !row.isCustom) Text(stringResource(R.string.lsp_customized), style = MaterialTheme.typography.bodySmall)
                Row {
                    if (row.isCustom && row.setInLayer) TextButton(onClick = onEdit) { Text(stringResource(R.string.lsp_edit)) }
                    if (row.setInLayer) {
                        TextButton(onClick = onRemove) {
                            Text(stringResource(if (row.isCustom) R.string.lsp_remove else R.string.lsp_reset_here))
                        }
                    }
                }
            }
        },
        trailingContent = { Switch(checked = row.enabled, onCheckedChange = onEnabled) },
    )
}

@Composable
private fun IssueRow(issue: ServerIssue) {
    val parts = buildList {
        if (RejectReason.NO_LANGUAGES in issue.reasons) add(stringResource(R.string.lsp_issue_no_languages))
        if (RejectReason.NO_COMMAND in issue.reasons) add(stringResource(R.string.lsp_issue_no_command))
        if (issue.reasons.isNotEmpty() && '/' in issue.key) add(stringResource(R.string.lsp_issue_not_declared))
        issue.badFields.forEach { add(stringResource(R.string.lsp_issue_field, it)) }
    }
    ListItem(
        headlineContent = { Text(issue.key, color = MaterialTheme.colorScheme.error) },
        supportingContent = { Column { parts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } },
    )
}

@Composable
private fun ServerFormDialog(key: String?, initial: Pair<String, String>, onSave: (String, String, String) -> Boolean, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(key.orEmpty()) }
    var languages by rememberSaveable { mutableStateOf(initial.first) }
    var command by rememberSaveable { mutableStateOf(initial.second) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (key == null) R.string.lsp_add else R.string.lsp_edit)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it; invalid = false }, label = { Text(stringResource(R.string.lsp_form_key)) }, singleLine = true,
                    isError = invalid && !LanguageServerRows.isValidCustomKey(name), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(languages, { languages = it; invalid = false }, label = { Text(stringResource(R.string.lsp_form_languages)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(command, { command = it; invalid = false }, label = { Text(stringResource(R.string.lsp_form_command)) },
                    minLines = 2, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.lsp_form_hint), style = MaterialTheme.typography.bodySmall)
                if (invalid) Text(stringResource(R.string.lsp_form_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(name, languages, command)) onDismiss() else invalid = true }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
