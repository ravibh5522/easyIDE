package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.screens.workspace.codeTextStyle

/** The content of the hover, signature help, quick-fix and code lens popups; [LspEditorOverlay] anchors them. */
@Composable
internal fun HoverContent(ui: HoverUi, controller: WorkspaceLspController) {
    Column {
        HoverBody(ui)
        if (ui.origin != HoverOrigin.MOUSE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.xs), modifier = Modifier.padding(Kit.space.xs)) {
                KitButton(stringResource(R.string.lsp_action_definition), style = KitButtonStyle.Ghost, onClick = {
                    controller.info.dismissHover()
                    controller.navigation.goTo(NavKind.DEFINITION, ui.path, ui.start)
                })
                KitButton(stringResource(R.string.lsp_action_references), style = KitButtonStyle.Ghost, onClick = {
                    controller.info.dismissHover()
                    controller.openLocations(NavKind.REFERENCES)
                })
                KitButton(stringResource(R.string.lsp_action_rename), style = KitButtonStyle.Ghost, onClick = {
                    controller.info.dismissHover()
                    controller.navigation.startRename()
                })
                KitButton(stringResource(R.string.lsp_action_quick_fix), icon = LspIcons.quickFix, style = KitButtonStyle.Ghost, onClick = {
                    controller.info.dismissHover()
                    controller.actions.openMenuAtCaret()
                })
            }
        }
    }
}

/** The hover text alone, without the touch action row. */
@Composable
internal fun HoverBody(ui: HoverUi) {
    LspMarkdownView(
        ui.markdown, ui.languageId, ui.fileName,
        Modifier
            .widthIn(max = LspUiMetrics.hoverMaxWidth)
            .heightIn(max = LspUiMetrics.popupMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Kit.space.s, vertical = Kit.space.xs),
    )
}

@Composable
internal fun SignatureContent(ui: SignatureUi) {
    val colors = Kit.colors
    val sig = ui.help.active ?: return
    val param = ui.help.activeParameter?.let { sig.parameters.getOrNull(it) }
    Column(
        modifier = Modifier
            .widthIn(max = LspUiMetrics.hoverMaxWidth)
            .heightIn(max = LspUiMetrics.popupMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Kit.space.s, vertical = Kit.space.xs),
    ) {
        BasicText(
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
            BasicText(
                stringResource(R.string.lsp_signature_count, ui.help.activeSignature + 1, ui.help.signatures.size),
                style = Kit.text.label.copy(color = colors.textMuted),
            )
        }
        param?.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
        sig.documentation?.let { LspMarkdownView(it.asMarkdown(), null, null) }
    }
}

@Composable
internal fun CodeActionContent(ui: CodeActionMenuUi, controller: WorkspaceLspController) {
    val colors = Kit.colors
    LazyColumn(modifier = Modifier.widthIn(max = LspUiMetrics.hoverMaxWidth).heightIn(max = LspUiMetrics.popupMaxHeight)) {
        itemsIndexed(ui.actions) { _, action ->
            val a = action.value
            val enabled = a.disabledReason == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LspUiMetrics.completionRowHeight)
                    .clickable(enabled = enabled) { controller.actions.run(action) }
                    .padding(horizontal = LspUiMetrics.rowPaddingH, vertical = LspUiMetrics.rowPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
            ) {
                Image(
                    LspIcons.quickFix,
                    null,
                    Modifier.size(LspUiMetrics.kindIconSize),
                    colorFilter = ColorFilter.tint(if (a.isPreferred && enabled) colors.decorations.lightbulb else colors.textMuted),
                )
                Column {
                    BasicText(a.title, style = Kit.text.body.copy(color = if (enabled) colors.plainText else colors.textDisabled))
                    a.disabledReason?.let { BasicText(it, style = Kit.text.label.copy(color = colors.textDisabled)) }
                }
            }
        }
    }
}

/** A gutter line's code lenses; a tap runs one. Unresolved lenses show a placeholder title. */
@Composable
internal fun CodeLensContent(ui: CodeLensMenuUi, controller: WorkspaceLspController) {
    val colors = Kit.colors
    LazyColumn(modifier = Modifier.widthIn(max = LspUiMetrics.hoverMaxWidth).heightIn(max = LspUiMetrics.popupMaxHeight)) {
        itemsIndexed(ui.lenses) { _, lens ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LspUiMetrics.completionRowHeight)
                    .clickable { controller.codeLens.run(lens) }
                    .padding(horizontal = LspUiMetrics.rowPaddingH, vertical = LspUiMetrics.rowPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
            ) {
                Image(LspIcons.codeLens, null, Modifier.size(LspUiMetrics.kindIconSize), colorFilter = ColorFilter.tint(colors.decorations.codeLens))
                BasicText(
                    lens.title ?: stringResource(R.string.lsp_code_lens_unresolved),
                    style = Kit.text.body.copy(color = if (lens.title != null) colors.plainText else colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
