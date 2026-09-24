package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.lsp.protocol.DiagnosticSeverity

/**
 * The right stage: Problems (LSP-20), References (LSP-25) and Outline (LSP-27) as tabs, the
 * way VS Code's panel shows them. Every row navigates on tap.
 */
@Composable
fun LspSidePanel(controller: WorkspaceLspController, panel: LspPanel, modifier: Modifier = Modifier) {
    val colors = Kit.colors
    val panels = LspPanel.entries
    Column(modifier = modifier.fillMaxHeight().background(colors.panel)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KitTabs(
                labels = panels.map { stringResource(it.title()) },
                selected = panels.indexOf(panel),
                onSelect = { controller.showPanel(panels[it]) },
                modifier = Modifier.weight(1f),
            )
            KitIconButton(Icons.Filled.Close, stringResource(R.string.lsp_panel_close), { controller.showPanel(null) })
        }
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

/** Severity to tone, so a filter tag and its rows agree; hints stay neutral. */
internal fun DiagnosticSeverity.tone(): Tone = when (this) {
    DiagnosticSeverity.ERROR -> Tone.Danger
    DiagnosticSeverity.WARNING -> Tone.Warning
    DiagnosticSeverity.INFORMATION -> Tone.Info
    DiagnosticSeverity.HINT -> Tone.Neutral
}

@Composable
private fun ProblemsPanel(controller: WorkspaceLspController) {
    val colors = Kit.colors
    val all by controller.diagnostics.problems.collectAsState()
    var shown by rememberSaveable { mutableStateOf(DiagnosticSeverity.entries.map { it.name }.toSet()) }
    val visible = ProblemsModel.visible(all, shown.map(DiagnosticSeverity::valueOf).toSet())
    val counts = ProblemsModel.counts(all)
    Row(modifier = Modifier.padding(horizontal = Kit.space.s), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        for (s in DiagnosticSeverity.entries) {
            val on = s.name in shown
            val n = when (s) {
                DiagnosticSeverity.ERROR -> counts.errors
                DiagnosticSeverity.WARNING -> counts.warnings
                DiagnosticSeverity.INFORMATION -> counts.infos
                DiagnosticSeverity.HINT -> counts.hints
            }
            KitTag(n.toString(), tone = s.tone(), selected = on, icon = LspIcons.severity(s), onClick = { shown = if (on) shown - s.name else shown + s.name })
        }
    }
    if (visible.isEmpty()) return EmptyPanel(EmptyArt.Problems, stringResource(R.string.lsp_problems_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in visible) {
            fileHeader(group.label, group.problems.size)
            items(group.problems) { p ->
                val origin = listOfNotNull(p.source, p.code?.let { "($it)" }).joinToString(" ")
                KitRow(
                    title = p.message,
                    subtitle = "$origin  ${p.location.range.start.line + 1}:${p.location.range.start.character + 1}".trim(),
                    leading = { Image(LspIcons.severity(p.severity), null, Modifier.size(LspUiMetrics.statusIconSize), colorFilter = ColorFilter.tint(LspIcons.severityTint(p.severity, colors))) },
                    onClick = { controller.navigate(p.location) },
                )
            }
        }
    }
}

@Composable
private fun ReferencesPanel(controller: WorkspaceLspController) {
    val colors = Kit.colors
    val ui by controller.navigation.locations.collectAsState()
    val refs = ui ?: return EmptyPanel(EmptyArt.Prompt, stringResource(R.string.lsp_references_empty))
    BasicText(
        stringResource(R.string.lsp_references_title, refs.symbol, refs.groups.sumOf { it.rows.size }),
        Modifier.padding(Kit.space.s),
        style = Kit.type.labelMedium.copy(color = colors.textMuted),
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in refs.groups) {
            fileHeader(group.label, group.rows.size)
            items(group.rows) { row ->
                KitRow(
                    title = row.preview,
                    mono = true,
                    trailing = { BasicText("${row.location.range.start.line + 1}", style = Kit.type.labelSmall.copy(fontFamily = EasyIdeFonts.mono, color = colors.textMuted)) },
                    onClick = { controller.navigate(row.location) },
                )
            }
        }
    }
}

@Composable
private fun OutlinePanel(controller: WorkspaceLspController) {
    val rows by controller.navigation.outline.collectAsState()
    if (rows.isEmpty()) return EmptyPanel(EmptyArt.Prompt, stringResource(R.string.lsp_outline_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows) { row -> SymbolRowView(row) { controller.navigate(row.location) } }
    }
}

/** A symbol: kind glyph, name, detail. Nesting shows as an indent from the row's start edge; a null [onClick] leaves the tap to the host. */
@Composable
internal fun SymbolRowView(row: SymbolRow, onClick: (() -> Unit)?) {
    KitRow(
        title = row.name,
        subtitle = row.detail,
        mono = true,
        modifier = Modifier.padding(start = LspUiMetrics.panelIndent * row.depth),
        leading = { Image(LspIcons.symbol(row.kind), null, Modifier.size(LspUiMetrics.kindIconSize), colorFilter = ColorFilter.tint(Kit.colors.accent)) },
        onClick = onClick,
    )
}

/** The file a group of rows belongs to: its path in mono with the row count, on the raised tone. */
private fun LazyListScope.fileHeader(label: String, count: Int) {
    item(key = "header:$label") {
        val colors = Kit.colors
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.raised).padding(horizontal = Kit.space.s, vertical = Kit.space.xs),
            horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
        ) {
            BasicText(label, Modifier.weight(1f), style = Kit.type.labelMedium.copy(fontFamily = EasyIdeFonts.mono, color = colors.plainText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(count.toString(), style = Kit.type.labelSmall.copy(fontFamily = EasyIdeFonts.mono, color = colors.textMuted))
        }
    }
}

@Composable
private fun EmptyPanel(art: EmptyArt, text: String) {
    KitEmptyState(art = art, message = text)
}
