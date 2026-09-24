package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.R
import dev.easyide.app.ui.commands.PickerOverlay
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.screens.workspace.NameInputDialog

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
        // The server's first offered action is the dialog's action; the rest are equal alternatives.
        KitDialog(
            title = q.key.serverId,
            onDismiss = { q.answer(null) },
            confirm = q.actions.firstOrNull()?.let { first -> KitAction(first) { q.answer(first) } },
            dismiss = KitAction(stringResource(R.string.lsp_action_dismiss)) { q.answer(null) },
        ) {
            BasicText(q.text, style = Kit.text.body.copy(color = Kit.colors.plainText))
            for (action in q.actions.drop(1)) {
                KitButton(action, { q.answer(action) }, Modifier.padding(top = Kit.space.s), KitButtonStyle.Secondary)
            }
        }
    }
    picker?.let { SymbolPicker(it, controller) }
}

@Composable
private fun RenamePreviewDialog(p: RenamePreviewUi, controller: WorkspaceLspController) {
    val colors = Kit.colors
    KitDialog(
        title = stringResource(R.string.lsp_rename_preview_title, p.newName),
        onDismiss = controller.navigation::cancelRename,
        confirm = KitAction(stringResource(R.string.lsp_rename_apply), controller.navigation::confirmRename),
        dismiss = KitAction(stringResource(R.string.lsp_cancel), controller.navigation::cancelRename),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = LspUiMetrics.popupMaxHeight)) {
            items(p.files) { (label, count) ->
                KitRow(
                    title = label,
                    mono = true,
                    trailing = { BasicText(stringResource(R.string.lsp_rename_edits, count), style = Kit.text.monoSmall.copy(color = colors.textMuted)) },
                )
            }
        }
    }
}

/**
 * The `@` (document symbols, LSP-27) and `#` (workspace symbols, LSP-31) quick pick, on the
 * command palette's own overlay: type to filter, arrows and Enter or tap to go, Escape to close.
 */
@Composable
private fun SymbolPicker(ui: SymbolPickerUi, controller: WorkspaceLspController) {
    var field by remember { mutableStateOf(TextFieldValue(ui.query)) }
    // Indexed, because a server may list two symbols with the same name and location.
    val rows = remember(ui.rows) { ui.rows.withIndex().toList() }

    PickerOverlay(
        value = field,
        onValueChange = { next -> field = next; controller.navigation.onPickerQuery(next.text) },
        hint = stringResource(if (ui.scope == SymbolScope.DOCUMENT) R.string.lsp_picker_document_hint else R.string.lsp_picker_workspace_hint),
        items = rows,
        itemKey = { it.index },
        onChoose = { (_, row) ->
            controller.navigation.closePicker()
            controller.navigate(row.location)
        },
        onDismiss = controller.navigation::closePicker,
        banner = {
            if (ui.rows.isEmpty()) {
                BasicText(
                    stringResource(if (ui.loading) R.string.lsp_picker_loading else R.string.lsp_picker_empty),
                    Modifier.fillMaxWidth().padding(Kit.space.m),
                    style = Kit.text.caption.copy(color = Kit.colors.textMuted),
                )
            }
        },
    ) { (_, row), _ ->
        SymbolRowView(row, onClick = null)
    }
}
