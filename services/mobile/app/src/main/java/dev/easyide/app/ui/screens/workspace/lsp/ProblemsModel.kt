package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.MinSeverity
import dev.easyide.lsp.diagnostics.DiagnosticSet
import dev.easyide.lsp.protocol.Diagnostic
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.session.ServerKey
import java.io.File

/**
 * A place navigation can open: a project file (editable, [projectPath] set) or a file of the
 * environment's rootfs (read-only, e.g. a library the server resolved a definition into).
 * [label] is what lists show: the project-relative path, or the guest path for env files.
 */
data class NavLocation(val projectPath: String?, val hostFile: File, val label: String, val range: Range)

/** One row of the Problems panel. A missing severity counts as an error (lsp `Diagnostic`). */
data class Problem(
    val location: NavLocation,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String?,
    val code: String?,
    val server: ServerKey,
)

/** One file's problems, most severe first, then by position. */
data class ProblemGroup(val label: String, val problems: List<Problem>)

data class ProblemCounts(val errors: Int, val warnings: Int, val infos: Int, val hints: Int) {
    companion object {
        val NONE = ProblemCounts(0, 0, 0, 0)
    }
}

/**
 * Builds the Problems panel from the diagnostic store (open and closed files alike) - pure, so
 * grouping, ordering and every filter is a unit test. The settings filters
 * (`editor.diagnostics.minSeverity`, `ignoreSources`) apply here exactly as to squiggles; the
 * panel's own severity toggles apply on top via [visible].
 */
object ProblemsModel {

    fun severityOf(d: Diagnostic): DiagnosticSeverity = d.severity ?: DiagnosticSeverity.ERROR

    /** Whether [d] passes the settings filters; the one rule squiggles and the panel share. */
    fun passes(d: Diagnostic, minSeverity: MinSeverity, ignoreSources: Set<String>): Boolean =
        severityOf(d).wire <= minSeverity.ordinal + 1 && (d.source == null || d.source !in ignoreSources)

    /**
     * Groups by file (sorted by label), rows by severity then position. Uris [locate] cannot
     * map (outside project and rootfs) are dropped: nothing could open them.
     */
    fun build(
        diagnostics: Map<String, Map<ServerKey, DiagnosticSet>>,
        locate: (uri: String, range: Range) -> NavLocation?,
        minSeverity: MinSeverity,
        ignoreSources: Set<String>,
    ): List<ProblemGroup> {
        val groups = ArrayList<ProblemGroup>()
        for ((uri, byServer) in diagnostics) {
            val rows = byServer.flatMap { (server, set) ->
                set.items.filter { passes(it, minSeverity, ignoreSources) }.mapNotNull { d ->
                    locate(uri, d.range)?.let { Problem(it, severityOf(d), d.message, d.source, d.code, server) }
                }
            }
            if (rows.isEmpty()) continue
            val sorted = rows.sortedWith(
                compareBy<Problem> { it.severity.wire }.thenBy { it.location.range.start.line }.thenBy { it.location.range.start.character },
            )
            groups += ProblemGroup(sorted.first().location.label, sorted)
        }
        return groups.sortedBy { it.label }
    }

    /** Only rows of the [visible] severities; groups left empty disappear. */
    fun visible(groups: List<ProblemGroup>, visible: Set<DiagnosticSeverity>): List<ProblemGroup> =
        groups.mapNotNull { g -> g.problems.filter { it.severity in visible }.takeIf { it.isNotEmpty() }?.let { g.copy(problems = it) } }

    fun counts(groups: List<ProblemGroup>): ProblemCounts {
        val all = groups.flatMap { it.problems }
        return ProblemCounts(
            errors = all.count { it.severity == DiagnosticSeverity.ERROR },
            warnings = all.count { it.severity == DiagnosticSeverity.WARNING },
            infos = all.count { it.severity == DiagnosticSeverity.INFORMATION },
            hints = all.count { it.severity == DiagnosticSeverity.HINT },
        )
    }
}
