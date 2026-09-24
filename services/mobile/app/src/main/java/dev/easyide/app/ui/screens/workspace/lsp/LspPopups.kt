package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.screens.workspace.codeTextStyle
import dev.easyide.app.ui.screens.workspace.decor.EditorGeometry
import dev.easyide.app.ui.screens.workspace.decor.EditorPopup

/**
 * Everything LSP draws over the editor text of [path]: completion, hover, signature help, the
 * quick-fix menu, a gutter line's code lenses and the snippet "next field" chip. Each is an [EditorPopup], so the text field
 * keeps focus and the soft keyboard stays up (decision 0018).
 */
@Composable
fun LspEditorOverlay(controller: WorkspaceLspController, path: String, geometry: EditorGeometry) {
    val completion by controller.completion.ui.collectAsState()
    val hover by controller.info.hover.collectAsState()
    val signature by controller.info.signature.collectAsState()
    val menu by controller.actions.menu.collectAsState()
    val lenses by controller.codeLens.menu.collectAsState()
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
    lenses?.takeIf { it.path == path }?.let { m ->
        EditorPopup(anchor = { geometry.caretRect(m.anchor) }, onDismiss = controller.codeLens::closeMenu) {
            CodeLensContent(m, controller)
        }
    }
    snippet?.takeIf { it.path == path && completion == null && it.session.hasNext }?.let {
        EditorPopup(anchor = { geometry.caretRect() }, onDismiss = controller.snippets::end) {
            KitButton(stringResource(R.string.lsp_snippet_next_field), { controller.snippets.move(forward = true) }, style = KitButtonStyle.Ghost)
        }
    }
}

@Composable
private fun CompletionContent(ui: CompletionUi, controller: WorkspaceLspController) {
    val colors = Kit.colors
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
                        .padding(Kit.space.s),
                ) {
                    focused.item.detail?.let { BasicText(it, style = codeTextStyle().copy(color = colors.textMuted)) }
                    focused.item.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
                }
            }
        }
        if (sideBySide) Row { list(); docs() } else Column { list(); docs() }
    }
}

@Composable
private fun CompletionRow(entry: CompletionEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = Kit.colors
    val item = entry.item
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LspUiMetrics.completionRowHeight)
            .background(if (selected) colors.listSelection else colors.overlay)
            .clickable(onClick = onClick)
            .padding(horizontal = LspUiMetrics.rowPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        Image(LspIcons.completion(item.kind), null, Modifier.size(LspUiMetrics.kindIconSize), colorFilter = ColorFilter.tint(colors.accent))
        val text = if (selected) colors.listSelectionText else colors.plainText
        BasicText(
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
            BasicText(it, style = Kit.text.label.copy(color = colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
