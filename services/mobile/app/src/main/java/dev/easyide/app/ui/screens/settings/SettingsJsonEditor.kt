package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.data.settings.JsonSuggestion
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.data.settings.Jsonc
import dev.easyide.app.data.settings.Severity
import dev.easyide.app.data.settings.SettingsDiagnostic

/**
 * "Edit as JSON" for one settings layer or keybindings.json (LLD sec 16): the
 * text, and below it every diagnostic with its line. Save stays disabled while
 * the text does not parse, so a half-typed edit is never written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsJsonEditor(
    state: JsonEditorState,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    suggest: (String, Int) -> List<JsonSuggestion> = { _, _ -> emptyList() },
) {
    // The caret is needed for completion, so the field keeps a TextFieldValue synced with state.text.
    var field by remember(state.document) { mutableStateOf(TextFieldValue(state.text)) }
    if (field.text != state.text) field = TextFieldValue(state.text, TextRange(field.selection.end.coerceAtMost(state.text.length)))
    val suggestions = remember(field.text, field.selection) {
        if (field.selection.collapsed) suggest(field.text, field.selection.end) else emptyList()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(titleOf(state.document))) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        TextButton(onClick = onSave, enabled = state.canSave) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.saveFailed) {
                    Text(
                        stringResource(R.string.settings_json_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { next ->
                        field = next
                        if (next.text != state.text) onTextChanged(next.text)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = Spacing.l),
                )
                if (suggestions.isNotEmpty()) {
                    SuggestionRow(suggestions) { s ->
                        val (text, caret) = s.applyTo(field.text)
                        field = TextFieldValue(text, TextRange(caret))
                        onTextChanged(text)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = Spacing.s))
                DiagnosticList(state.text, state.diagnostics)
            }
        }
    }
}

/** Key / value completions (LLD 16), tapped to insert at the caret. */
@Composable
private fun SuggestionRow(suggestions: List<JsonSuggestion>, onPick: (JsonSuggestion) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        items(suggestions) { s ->
            SuggestionChip(
                onClick = { onPick(s) },
                label = { Text(if (s.detail != null) "${s.label}  ${s.detail}" else s.label, fontFamily = FontFamily.Monospace) },
            )
        }
    }
}

@Composable
private fun DiagnosticList(text: String, diagnostics: List<SettingsDiagnostic>) {
    if (diagnostics.isEmpty()) {
        Text(
            stringResource(R.string.settings_json_no_problems),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(Spacing.l),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = DIAGNOSTICS_MAX_HEIGHT_DP.dp)) {
        items(diagnostics) { d ->
            val line = d.offset?.let { Jsonc.lineOf(text, it) }
            val message = diagnosticText(d)
            Text(
                text = if (line != null) stringResource(R.string.settings_json_line, line, message) else message,
                style = MaterialTheme.typography.bodySmall,
                color = when (d.severity) {
                    Severity.ERROR -> MaterialTheme.colorScheme.error
                    Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                    Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xxs),
            )
        }
    }
}

/** Every diagnostic string takes the key as `%1$s` and the detail as `%2$s`. */
@Composable
fun diagnosticText(d: SettingsDiagnostic): String {
    val detail = d.parseError?.let { stringResource(it.message) } ?: d.detail.orEmpty()
    return stringResource(d.code.message, d.key.orEmpty(), detail)
}

private fun titleOf(document: JsonDocument): Int = when (document) {
    JsonDocument.Keybindings -> R.string.settings_json_keybindings_title
    is JsonDocument.SettingsLayer -> when (document.target.layer) {
        dev.easyide.app.data.settings.LayerId.ENVIRONMENT -> R.string.settings_json_environment_title
        dev.easyide.app.data.settings.LayerId.PROJECT -> R.string.settings_json_project_title
        else -> R.string.settings_json_user_title
    }
}

private const val DIAGNOSTICS_MAX_HEIGHT_DP = 200
