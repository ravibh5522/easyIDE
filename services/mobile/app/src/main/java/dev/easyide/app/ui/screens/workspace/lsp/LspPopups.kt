package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.codeTextStyle
import dev.easyide.app.ui.screens.workspace.decor.EditorGeometry
import dev.easyide.app.ui.screens.workspace.decor.EditorPopup
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors

/**
 * Everything LSP draws over the editor text of [path]: completion, hover, signature help, the
 * quick-fix menu and the snippet "next field" chip. Each is an [EditorPopup], so the text field
 * keeps focus and the soft keyboard stays up (decision 0018).
 */
@Composable
fun LspEditorOverlay(controller: WorkspaceLspController, path: String, geometry: EditorGeometry) {
    val completion by controller.completion.ui.collectAsState()
    val hover by controller.info.hover.collectAsState()
    val signature by controller.info.signature.collectAsState()
    val menu by controller.actions.menu.collectAsState()
    val snippet by controller.snippets.active.collectAsState()

    signature?.takeIf { it.path == path }?.let { s ->
        EditorPopup(anchor = { geometry.caretRect() }, onDismiss = controller.info::dismissSignature, preferAbove = true) {
            SignatureContent(s)
        }
    }
    completion?.takeIf { it.path == path }?.let { c ->
        EditorPopup(anchor = { geometry.caretRect() }, onDismiss = controller.completion::close) {
            CompletionContent(c, controller)
        }
    }
    hover?.takeIf { it.path == path && completion == null }?.let { h ->
        EditorPopup(anchor = { geometry.rangeRect(h.start, h.end) }, onDismiss = controller.info::dismissHover) {
            HoverContent(h, controller)
        }
    }
    menu?.takeIf { it.path == path }?.let { m ->
        EditorPopup(anchor = { geometry.caretRect(m.anchor) }, onDismiss = controller.actions::closeMenu) {
            CodeActionContent(m, controller)
        }
    }
    snippet?.takeIf { it.path == path && completion == null && it.session.hasNext }?.let {
        EditorPopup(anchor = { geometry.caretRect() }, onDismiss = controller.snippets::end) {
            ChromeButton(text = stringResource(R.string.lsp_snippet_next_field), onClick = { controller.snippets.move(forward = true) })
        }
    }
}

@Composable
private fun CompletionContent(ui: CompletionUi, controller: WorkspaceLspController) {
    val colors = editorColors
    val listState = rememberLazyListState()
    LaunchedEffect(ui.selected) { listState.scrollToItem((ui.selected - LspUiPolicy.COMPLETION_VISIBLE_ROWS / 2).coerceAtLeast(0)) }
    BoxWithConstraints {
        val sideBySide = maxWidth >= LspUiMetrics.completionWidth + LspUiMetrics.completionDocWidth
        val list: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(LspUiMetrics.completionWidth)
                    .heightIn(max = LspUiMetrics.completionRowHeight * LspUiPolicy.COMPLETION_VISIBLE_ROWS),
            ) {
                itemsIndexed(ui.items, key = { i, _ -> i }) { index, entry ->
                    CompletionRow(entry, index == ui.selected) { controller.completion.acceptAsync(index, AcceptMode.INSERT) }
                }
            }
        }
        val focused = ui.focused
        val docs: @Composable () -> Unit = {
            if (focused != null && (focused.item.detail != null || focused.item.documentation != null)) {
                Column(
                    modifier = Modifier
                        .widthIn(max = LspUiMetrics.completionDocWidth)
                        .heightIn(max = LspUiMetrics.popupMaxHeight)
                        .background(colors.raised)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.s),
                ) {
                    focused.item.detail?.let { Text(it, style = codeTextStyle().copy(color = colors.textMuted)) }
                    focused.item.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
                }
            }
        }
        if (sideBySide) Row { list(); docs() } else Column { list(); docs() }
    }
}

@Composable
private fun CompletionRow(entry: CompletionEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = editorColors
    val item = entry.item
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LspUiMetrics.completionRowHeight)
            .background(if (selected) colors.listSelection else colors.overlay)
            .clickable(onClick = onClick)
            .padding(horizontal = LspUiMetrics.rowPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(LspIcons.completion(item.kind), contentDescription = null, tint = colors.accent, modifier = Modifier.size(LspUiMetrics.kindIconSize))
        val text = if (selected) colors.listSelectionText else colors.plainText
        Text(
            text = buildAnnotatedString {
                append(item.label)
                item.labelDetail?.let { withStyle(SpanStyle(color = colors.textMuted)) { append(it) } }
            },
            style = codeTextStyle().copy(color = if (item.deprecated) colors.textDisabled else text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        (item.labelDescription ?: item.detail)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HoverContent(ui: HoverUi, controller: WorkspaceLspController) {
    Column(modifier = Modifier.widthIn(max = LspUiMetrics.hoverMaxWidth)) {
        LspMarkdownView(
            ui.markdown, ui.languageId, ui.fileName,
            Modifier
                .heightIn(max = LspUiMetrics.popupMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.s),
        )
        if (ui.origin != HoverOrigin.MOUSE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(Spacing.xs)) {
                ChromeButton(stringResource(R.string.lsp_action_definition), onClick = {
                    controller.info.dismissHover()
                    controller.navigation.goTo(NavKind.DEFINITION, ui.path, ui.start)
                })
                ChromeButton(stringResource(R.string.lsp_action_references), onClick = {
                    controller.info.dismissHover()
                    controller.openLocations(NavKind.REFERENCES)
                })
                ChromeButton(stringResource(R.string.lsp_action_rename), onClick = {
                    controller.info.dismissHover()
                    controller.navigation.startRename()
                })
                ChromeButton(stringResource(R.string.lsp_action_quick_fix), icon = LspIcons.quickFix, onClick = {
                    controller.info.dismissHover()
                    controller.actions.openMenuAtCaret()
                })
            }
        }
    }
}

@Composable
private fun SignatureContent(ui: SignatureUi) {
    val colors = editorColors
    val sig = ui.help.active ?: return
    val param = ui.help.activeParameter?.let { sig.parameters.getOrNull(it) }
    Column(
        modifier = Modifier
            .widthIn(max = LspUiMetrics.hoverMaxWidth)
            .heightIn(max = LspUiMetrics.popupMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.s),
    ) {
        Text(
            text = buildAnnotatedString {
                if (param == null) {
                    append(sig.label)
                } else {
                    append(sig.label.substring(0, param.labelStart))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.accent)) {
                        append(sig.label.substring(param.labelStart, param.labelEnd))
                    }
                    append(sig.label.substring(param.labelEnd))
                }
            },
            style = codeTextStyle().copy(color = colors.plainText),
        )
        if (ui.help.signatures.size > 1) {
            Text(
                stringResource(R.string.lsp_signature_count, ui.help.activeSignature + 1, ui.help.signatures.size),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        param?.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
        sig.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
    }
}

@Composable
private fun CodeActionContent(ui: CodeActionMenuUi, controller: WorkspaceLspController) {
    val colors = editorColors
    LazyColumn(modifier = Modifier.widthIn(max = LspUiMetrics.hoverMaxWidth).heightIn(max = LspUiMetrics.popupMaxHeight)) {
        itemsIndexed(ui.actions) { _, action ->
            val a = action.value
            val enabled = a.disabledReason == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { controller.actions.run(action) }
                    .padding(horizontal = LspUiMetrics.rowPaddingH, vertical = LspUiMetrics.rowPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Icon(
                    LspIcons.quickFix,
                    contentDescription = null,
                    tint = if (a.isPreferred && enabled) colors.decorations.lightbulb else colors.textMuted,
                    modifier = Modifier.size(LspUiMetrics.kindIconSize),
                )
                Column {
                    Text(a.title, style = MaterialTheme.typography.bodySmall, color = if (enabled) colors.plainText else colors.textDisabled)
                    a.disabledReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textDisabled) }
                }
            }
        }
    }
}
