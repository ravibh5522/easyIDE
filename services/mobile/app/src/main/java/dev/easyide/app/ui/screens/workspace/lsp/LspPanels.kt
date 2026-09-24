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
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.CountBadge
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.screens.workspace.ClosedSections
import dev.easyide.app.ui.screens.workspace.PanelTabRow
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.rememberClosedSections
import dev.easyide.lsp.protocol.DiagnosticSeverity

/**
 * The right stage: Problems (LSP-20), References (LSP-25) and Outline (LSP-27) as tabs, the
 * way VS Code's panel shows them. Every row navigates on tap.
 */
@Composable
fun LspSidePanel(controller: WorkspaceLspController, panel: LspPanel, modifier: Modifier = Modifier) {
    LspPanelFrame(panel, { controller.showPanel(it) }, { controller.showPanel(null) }, modifier) {
        when (panel) {
            LspPanel.PROBLEMS -> ProblemsPanel(controller)
            LspPanel.REFERENCES -> ReferencesPanel(controller)
            LspPanel.OUTLINE -> OutlinePanel(controller)
        }
    }
}

/** The panel's tab row over its [content]; a frame of its own so the row and a list can be drawn without a language server. */
@Composable
internal fun LspPanelFrame(panel: LspPanel, onSelect: (LspPanel) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val panels = LspPanel.entries
    Column(modifier = modifier.fillMaxHeight().background(Kit.colors.panel)) {
        PanelTabRow(panels.map { stringResource(it.title()) }, panels.indexOf(panel), { onSelect(panels[it]) }) {
            KitIconButton(Icons.Filled.Close, stringResource(R.string.lsp_panel_close), onClose)
        }
        content()
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
    val all by controller.diagnostics.problems.collectAsState()
    ProblemsView(all, controller::navigate)
}

@Composable
internal fun ProblemsView(all: List<ProblemGroup>, onNavigate: (NavLocation) -> Unit) {
    val colors = Kit.colors
    var shown by rememberSaveable { mutableStateOf(DiagnosticSeverity.entries.map { it.name }.toSet()) }
    val closed = rememberClosedSections()
    val visible = ProblemsModel.visible(all, shown.map(DiagnosticSeverity::valueOf).toSet())
    val counts = ProblemsModel.counts(all)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = Kit.space.xs),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    if (visible.isEmpty()) return EmptyPanel(stringResource(R.string.lsp_problems_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in visible) {
            fileGroup(closed, group.label, group.problems.size) {
                items(group.problems) { p ->
                    val origin = listOfNotNull(p.source, p.code?.let { "($it)" }).joinToString(" ")
                    KitRow(
                        title = p.message,
                        subtitle = "$origin  ${p.location.range.start.line + 1}:${p.location.range.start.character + 1}".trim(),
                        leading = { Image(LspIcons.severity(p.severity), null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(LspIcons.severityTint(p.severity, colors))) },
                        twistie = Twistie.Leaf,
                        onClick = { onNavigate(p.location) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferencesPanel(controller: WorkspaceLspController) {
    val colors = Kit.colors
    val ui by controller.navigation.locations.collectAsState()
    val closed = rememberClosedSections()
    val refs = ui ?: return EmptyPanel(stringResource(R.string.lsp_references_empty))
    BasicText(
        stringResource(R.string.lsp_references_title, refs.symbol, refs.groups.sumOf { it.rows.size }),
        Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = Kit.space.xs),
        style = Kit.text.caption.copy(color = colors.textMuted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in refs.groups) {
            fileGroup(closed, group.label, group.rows.size) {
                items(group.rows) { row ->
                    KitRow(
                        title = row.preview,
                        mono = true,
                        twistie = Twistie.Leaf,
                        trailing = { BasicText("${row.location.range.start.line + 1}", style = Kit.text.monoSmall.copy(color = colors.textMuted)) },
                        onClick = { controller.navigate(row.location) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlinePanel(controller: WorkspaceLspController) {
    val rows by controller.navigation.outline.collectAsState()
    if (rows.isEmpty()) return EmptyPanel(stringResource(R.string.lsp_outline_empty))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows) { row -> SymbolRowView(row) { controller.navigate(row.location) } }
    }
}

/** A symbol: kind glyph, name, detail inline. Nesting is the row's indent level; a null [onClick] leaves the tap to the host. */
@Composable
internal fun SymbolRowView(row: SymbolRow, onClick: (() -> Unit)?) {
    KitRow(
        title = row.name,
        subtitle = row.detail,
        level = row.depth,
        leading = { Image(LspIcons.symbol(row.kind), null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(Kit.colors.accent)) },
        onClick = onClick,
    )
}

/** The file a group of rows belongs to, as a row: twistie, file icon, name, its directory inline and the row count; a tap folds it. */
private fun LazyListScope.fileGroup(closed: ClosedSections, label: String, count: Int, rows: LazyListScope.() -> Unit) {
    val open = closed.isOpen(label)
    item(key = "header:$label") {
        val name = label.substringAfterLast('/')
        KitRow(
            title = name,
            subtitle = label.substringBeforeLast('/', "").ifEmpty { null },
            twistie = if (open) Twistie.Expanded else Twistie.Collapsed,
            leading = { FileIcon(name, size = Kit.control.rowIcon) },
            trailing = { CountBadge(count) },
            onClick = { closed.toggle(label) },
        )
    }
    if (open) rows()
}

@Composable
private fun EmptyPanel(text: String) {
    KitRow(text, enabled = false)
}
