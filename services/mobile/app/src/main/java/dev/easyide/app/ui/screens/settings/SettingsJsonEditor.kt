package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.data.settings.JsonSuggestion
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Jsonc
import dev.easyide.app.data.settings.SettingsDiagnostic
import dev.easyide.app.data.settings.Severity
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * "Edit as JSON" for one settings layer or keybindings.json (LLD sec 16): the text, and below it
 * every diagnostic with its line. Save stays disabled while the text does not parse, so a half-typed
 * edit is never written. The text area is a plain text field rather than a `KitField` because
 * completion needs the caret, which `KitField` does not expose; it is drawn from the same tokens.
 */
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
        KitScaffold(
            title = stringResource(titleOf(state.document)),
            onBack = onClose,
            actions = { KitButton(stringResource(R.string.action_save), onSave, enabled = state.canSave) },
            modifier = Modifier.imePadding(),
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
                if (state.saveFailed) BodyText(stringResource(R.string.settings_json_save_failed), Modifier.padding(Kit.space.l), Tone.Danger)
                JsonTextArea(field) { next ->
                    field = next
                    if (next.text != state.text) onTextChanged(next.text)
                }
                if (suggestions.isNotEmpty()) {
                    SuggestionRow(suggestions) { s ->
                        val (text, caret) = s.applyTo(field.text)
                        field = TextFieldValue(text, TextRange(caret))
                        onTextChanged(text)
                    }
                }
                DiagnosticList(state.text, state.diagnostics)
            }
        }
    }
}

@Composable
private fun JsonTextArea(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = monoStyle(Kit.type.bodySmall).copy(color = colors.plainText),
        cursorBrush = SolidColor(colors.cursor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Kit.space.l, vertical = Kit.space.s)
            .background(colors.background, shape)
            .border(Kit.hairline, colors.panelBorder, shape)
            .padding(Kit.space.m),
    )
}

/** Key / value completions (LLD 16), tapped to insert at the caret. */
@Composable
private fun SuggestionRow(suggestions: List<JsonSuggestion>, onPick: (JsonSuggestion) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Kit.space.l, vertical = Kit.space.xs),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        suggestions.forEach { s -> KitTag(if (s.detail != null) "${s.label}  ${s.detail}" else s.label, onClick = { onPick(s) }) }
    }
}

@Composable
private fun DiagnosticList(text: String, diagnostics: List<SettingsDiagnostic>) {
    if (diagnostics.isEmpty()) {
        BodyText(stringResource(R.string.settings_json_no_problems), Modifier.padding(Kit.space.l))
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Kit.space.l, vertical = Kit.space.s), Arrangement.spacedBy(Kit.space.xxs)) {
        diagnostics.forEach { d ->
            val line = d.offset?.let { Jsonc.lineOf(text, it) }
            val message = diagnosticText(d)
            BodyText(
                if (line != null) stringResource(R.string.settings_json_line, line, message) else message,
                tone = when (d.severity) {
                    Severity.ERROR -> Tone.Danger
                    Severity.WARNING -> Tone.Warning
                    Severity.INFO -> Tone.Neutral
                },
                mono = true,
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
        LayerId.ENVIRONMENT -> R.string.settings_json_environment_title
        LayerId.PROJECT -> R.string.settings_json_project_title
        else -> R.string.settings_json_user_title
    }
}
