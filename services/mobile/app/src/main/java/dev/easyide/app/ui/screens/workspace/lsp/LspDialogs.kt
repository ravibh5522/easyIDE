package dev.easyide.app.ui.screens.workspace.lsp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.DenseTextField
import dev.easyide.app.ui.screens.workspace.NameInputDialog
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors

/** Rename input and preview, server questions and the symbol quick pick of one workspace. */
@Composable
fun LspDialogs(controller: WorkspaceLspController) {
    val rename by controller.navigation.rename.collectAsState()
    val preview by controller.navigation.renamePreview.collectAsState()
    val questions by controller.questions.collectAsState()
    val picker by controller.navigation.picker.collectAsState()

    rename?.let { r ->
        NameInputDialog(
            title = stringResource(R.string.lsp_rename_title),
            initialValue = r.placeholder,
            confirmLabel = stringResource(R.string.lsp_rename_preview),
            onConfirm = controller.navigation::submitRename,
            onDismiss = controller.navigation::cancelRename,
        )
    }
    preview?.let { p -> RenamePreviewDialog(p, controller) }
    questions.firstOrNull()?.let { q ->
        AlertDialog(
            onDismissRequest = { q.answer(null) },
            title = { Text(q.key.serverId) },
            text = { Text(q.text) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (action in q.actions) TextButton(onClick = { q.answer(action) }) { Text(action) }
                }
            },
            dismissButton = { TextButton(onClick = { q.answer(null) }) { Text(stringResource(R.string.lsp_action_dismiss)) } },
        )
    }
    picker?.let { SymbolPicker(it, controller) }
}

@Composable
private fun RenamePreviewDialog(p: RenamePreviewUi, controller: WorkspaceLspController) {
    val colors = editorColors
    AlertDialog(
        onDismissRequest = controller.navigation::cancelRename,
        title = { Text(stringResource(R.string.lsp_rename_preview_title, p.newName)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = LspUiMetrics.popupMaxHeight)) {
                items(p.files) { (label, count) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.plainText, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.lsp_rename_edits, count), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = controller.navigation::confirmRename) { Text(stringResource(R.string.lsp_rename_apply)) } },
        dismissButton = { TextButton(onClick = controller.navigation::cancelRename) { Text(stringResource(R.string.lsp_cancel)) } },
    )
}

/**
 * The `@` (document symbols, LSP-27) and `#` (workspace symbols, LSP-31) quick pick, in the
 * command palette's place and shape: type to filter, Enter or tap to go, Escape to close.
 */
@Composable
private fun SymbolPicker(ui: SymbolPickerUi, controller: WorkspaceLspController) {
    val colors = editorColors
    val focus = remember { FocusRequester() }
    BackHandler(onBack = controller.navigation::closePicker)
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun go(row: SymbolRow) {
        controller.navigation.closePicker()
        controller.navigate(row.location)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = null, indication = null, onClick = controller.navigation::closePicker),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = Spacing.xxxl, start = Spacing.l, end = Spacing.l)
                .widthIn(max = LspUiMetrics.panelWidth * 2)
                .fillMaxWidth()
                .background(colors.overlay)
                .border(Stroke.hairline, colors.panelBorder)
                .clickable(interactionSource = null, indication = null, onClick = {}),
        ) {
            val hint = stringResource(if (ui.scope == SymbolScope.DOCUMENT) R.string.lsp_picker_document_hint else R.string.lsp_picker_workspace_hint)
            DenseTextField(
                value = ui.query,
                onValueChange = controller.navigation::onPickerQuery,
                placeholder = hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.s)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> true.also { ui.rows.firstOrNull()?.let(::go) }
                            Key.Escape -> true.also { controller.navigation.closePicker() }
                            else -> false
                        }
                    },
            )
            if (ui.rows.isEmpty()) {
                Text(
                    stringResource(if (ui.loading) R.string.lsp_picker_loading else R.string.lsp_picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(Spacing.m),
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = LspUiMetrics.popupMaxHeight * 2)) {
                items(ui.rows) { row -> SymbolRowView(row) { go(row) } }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.4f
