package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.lsp.protocol.DiagnosticSeverity

/**
 * The right stage: Problems (LSP-20), References (LSP-25) and Outline (LSP-27) as tabs, the
 * way VS Code's panel shows them. Every row navigates on tap.
 */
@Composable
fun LspSidePanel(controller: WorkspaceLspController, panel: LspPanel, modifier: Modifier = Modifier) {
    val colors = editorColors
    Column(modifier = modifier.fillMaxHeight().background(colors.panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ControlSize.tab).padding(start = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in LspPanel.entries) {
                val active = p == panel
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { controller.showPanel(p) }
                        .padding(horizontal = Spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(p.title()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) colors.plainText else colors.textMuted,
                    )
                    if (active) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Stroke.accentBar).background(colors.accent))
                    }
                }
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { controller.showPanel(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lsp_panel_close), tint = colors.textMuted)
            }
        }
        Box(Modifier.fillMaxWidth().height(Stroke.hairline).background(colors.panelBorder))
        when (panel) {
            LspPanel.PROBLEMS -> ProblemsPanel(controller)
            LspPanel.REFERENCES -> ReferencesPanel(controller)
            LspPanel.OUTLINE -> OutlinePanel(controller)
        }
    }
}

private fun LspPanel.title(): Int = when (this) {
    LspPanel.PROBLEMS -> R.string.lsp_panel_problems
    LspPanel.REFERENCES -> R.string.lsp_panel_references
    LspPanel.OUTLINE -> R.string.lsp_panel_outline
}

@Composable
private fun ProblemsPanel(controller: WorkspaceLspController) {
    val colors = editorColors
    val all by controller.diagnostics.problems.collectAsState()
    var shown by rememberSaveable { mutableStateOf(DiagnosticSeverity.entries.map { it.name }.toSet()) }
    val visible = ProblemsModel.visible(all, shown.map(DiagnosticSeverity::valueOf).toSet())
    val counts = ProblemsModel.counts(all)
    Row(modifier = Modifier.padding(Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for (s in DiagnosticSeverity.entries) {
            val on = s.name in shown
            val n = when (s) {
                DiagnosticSeverity.ERROR -> counts.errors
                DiagnosticSeverity.WARNING -> counts.warnings
                DiagnosticSeverity.INFORMATION -> counts.infos
                DiagnosticSeverity.HINT -> counts.hints
            }
            Row(
                modifier = Modifier
                    .background(if (on) colors.raised else colors.panel, MaterialTheme.shapes.small)
                    .clickable { shown = if (on) shown - s.name else shown + s.name }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(LspIcons.severity(s), null, tint = if (on) LspIcons.severityTint(s, colors) else colors.textDisabled, modifier = Modifier.size(LspUiMetrics.statusIconSize))
                Text(n.toString(), style = MaterialTheme.typography.labelSmall, color = if (on) colors.plainText else colors.textDisabled)
            }
        }
    }
    if (visible.isEmpty()) return EmptyPanel(stringResource(R.string.lsp_problems_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in visible) {
            fileHeader(group.label, group.problems.size)
            items(group.problems) { p ->
                PanelRow(onClick = { controller.navigate(p.location) }) {
                    Icon(LspIcons.severity(p.severity), null, tint = LspIcons.severityTint(p.severity, colors), modifier = Modifier.size(LspUiMetrics.statusIconSize))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.message, style = MaterialTheme.typography.bodySmall, color = colors.plainText)
                        val origin = listOfNotNull(p.source, p.code?.let { "($it)" }).joinToString(" ")
                        Text(
                            "$origin  ${p.location.range.start.line + 1}:${p.location.range.start.character + 1}".trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferencesPanel(controller: WorkspaceLspController) {
    val colors = editorColors
    val ui by controller.navigation.locations.collectAsState()
    val refs = ui ?: return EmptyPanel(stringResource(R.string.lsp_references_empty))
    Text(
        stringResource(R.string.lsp_references_title, refs.symbol, refs.groups.sumOf { it.rows.size }),
        style = MaterialTheme.typography.labelMedium,
        color = colors.textMuted,
        modifier = Modifier.padding(Spacing.s),
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in refs.groups) {
            fileHeader(group.label, group.rows.size)
            items(group.rows) { row ->
                PanelRow(onClick = { controller.navigate(row.location) }) {
                    Text("${row.location.range.start.line + 1}", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                    Text(row.preview, style = MaterialTheme.typography.bodySmall, color = colors.plainText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun OutlinePanel(controller: WorkspaceLspController) {
    val colors = editorColors
    val rows by controller.navigation.outline.collectAsState()
    if (rows.isEmpty()) return EmptyPanel(stringResource(R.string.lsp_outline_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows) { row -> SymbolRowView(row) { controller.navigate(row.location) } }
    }
}

@Composable
internal fun SymbolRowView(row: SymbolRow, onClick: () -> Unit) {
    val colors = editorColors
    PanelRow(onClick = onClick, indent = row.depth) {
        Icon(LspIcons.symbol(row.kind), null, tint = colors.accent, modifier = Modifier.size(LspUiMetrics.kindIconSize))
        Text(row.name, style = MaterialTheme.typography.bodySmall, color = colors.plainText, maxLines = 1)
        row.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun LazyListScope.fileHeader(label: String, count: Int) {
    item(key = "header:$label") {
        val colors = editorColors
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.raised).padding(horizontal = Spacing.s, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.plainText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

@Composable
private fun PanelRow(onClick: () -> Unit, indent: Int = 0, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.s + LspUiMetrics.panelIndent * indent, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) { content() }
}

@Composable
private fun EmptyPanel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = editorColors.textMuted, modifier = Modifier.padding(Spacing.m))
}
