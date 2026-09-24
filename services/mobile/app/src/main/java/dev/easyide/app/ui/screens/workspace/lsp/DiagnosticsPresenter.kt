package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.MinSeverity
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.app.ui.screens.workspace.decor.DiagnosticDecoration
import dev.easyide.lsp.diagnostics.DiagnosticSet
import dev.easyide.lsp.protocol.Diagnostic
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.text.LineIndex
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.easyide.app.ui.screens.workspace.decor.DiagnosticSeverity as DecorSeverity

/** The diagnostics settings, compared so an unrelated setting change does not repaint. */
private data class DiagnosticFilters(
    val minSeverity: MinSeverity,
    val ignoreSources: Set<String>,
    val squiggles: Boolean,
    val gutter: Boolean,
) {
    companion object {
        /** The filters for [languageId] (`[lang]` blocks apply); null for files of any language. */
        fun of(s: SettingsSnapshot, languageId: String?) = DiagnosticFilters(
            s.get(LspSettingsSchema.diagnosticsMinSeverity, languageId),
            s.get(LspSettingsSchema.diagnosticsIgnoreSources, languageId).toSet(),
            s.get(LspSettingsSchema.diagnosticsShowSquiggles, languageId),
            s.get(LspSettingsSchema.diagnosticsShowInGutter, languageId),
        )
    }
}

/**
 * Diagnostics (LSP-20) from the project's store to squiggles and gutter icons of open
 * documents, and to the Problems panel for every file. Each server writes its own decoration
 * source (`lsp:<serverId>`), so one server's fresh list replaces only its own marks.
 *
 * A set computed for an older version is placed against that version's text, and the
 * decoration model carries it onto the buffer; one too old to be remembered waits for the
 * server's next publish (lsp-features.md 4.1).
 */
class DiagnosticsPresenter(private val ws: LspWorkspace) {

    private val problemsFlow = MutableStateFlow<List<ProblemGroup>>(emptyList())
    private val countsFlow = MutableStateFlow(ProblemCounts.NONE)
    private val written = HashMap<String, Set<String>>()

    val problems: StateFlow<List<ProblemGroup>> = problemsFlow.asStateFlow()
    val counts: StateFlow<ProblemCounts> = countsFlow.asStateFlow()

    /** The latest store snapshot, for code-action context. */
    @Volatile private var latest: Map<String, Map<ServerKey, DiagnosticSet>> = emptyMap()

    fun start() {
        ws.scope.launch {
            // Any settings change repaints: the per-language filters are read per document in paint.
            combine(ws.client.diagnostics(ws.environmentId, ws.projectId), ws.settingsState, ws.documents.paths) { d, s, _ -> d to DiagnosticFilters.of(s, null) }
                .conflate()
                .map { (d, f) -> Triple(d, f, ProblemsModel.build(d, ws::location, f.minSeverity, f.ignoreSources)) }
                .flowOn(Dispatchers.Default)
                .collect { (d, f, groups) ->
                    latest = d
                    problemsFlow.value = groups
                    countsFlow.value = ProblemsModel.counts(groups)
                    paint(d)
                }
        }
    }

    /** Diagnostics of every server overlapping lines [firstLine]..[lastLine] of [path]. */
    fun onLines(path: String, firstLine: Int, lastLine: Int): List<Diagnostic> {
        val uri = ws.documents.doc(path)?.uri ?: return emptyList()
        return latest[FileUri.canonical(uri)].orEmpty().values.flatMap { it.items }
            .filter { it.range.start.line <= lastLine && it.range.end.line >= firstLine }
    }

    private fun paint(all: Map<String, Map<ServerKey, DiagnosticSet>>) {
        for (doc in ws.documents.open) {
            val f = DiagnosticFilters.of(ws.settings, doc.languageId)
            val byServer = all[doc.uri].orEmpty()
            val model = ws.decorations.model(doc.path)
            val sources = HashSet<String>()
            for ((server, set) in byServer) {
                val text = ws.documents.textAt(doc.path, set.version) ?: continue
                val source = sourceOf(server)
                sources += source
                model.set(DecorationLayer.Diagnostics, source, decorations(set.items, text, f), text)
            }
            for (gone in written[doc.path].orEmpty() - sources) model.clear(DecorationLayer.Diagnostics, gone)
            written[doc.path] = sources
            // A set placed against an older text moved the model back; carry it onto the buffer
            // now so the painter (which skips mismatched frames) is not left blank until a keystroke.
            ws.tab(doc.path)?.content?.let(model::syncText)
        }
        written.keys.retainAll(ws.documents.open.mapTo(HashSet()) { it.path })
    }

    private fun decorations(items: List<Diagnostic>, text: String, f: DiagnosticFilters): List<DiagnosticDecoration> {
        val lines = LineIndex(text)
        return items.filter { ProblemsModel.passes(it, f.minSeverity, f.ignoreSources) }.map { d ->
            DiagnosticDecoration(
                start = lines.offset(d.range.start),
                end = lines.offset(d.range.end),
                severity = decorSeverity(ProblemsModel.severityOf(d)),
                showSquiggle = f.squiggles,
                showInGutter = f.gutter,
            )
        }
    }

    companion object {
        fun sourceOf(server: ServerKey): String = "lsp:${server.serverId}"

        fun decorSeverity(s: DiagnosticSeverity): DecorSeverity = when (s) {
            DiagnosticSeverity.ERROR -> DecorSeverity.ERROR
            DiagnosticSeverity.WARNING -> DecorSeverity.WARNING
            DiagnosticSeverity.INFORMATION -> DecorSeverity.INFORMATION
            DiagnosticSeverity.HINT -> DecorSeverity.HINT
        }
    }
}
