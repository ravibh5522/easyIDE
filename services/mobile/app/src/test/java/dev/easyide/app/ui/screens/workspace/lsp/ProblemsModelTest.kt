package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.MinSeverity
import dev.easyide.lsp.diagnostics.DiagnosticSet
import dev.easyide.lsp.protocol.Diagnostic
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.session.ServerKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProblemsModelTest {

    private val pyright = ServerKey("e", "p", "pyright")
    private val ruff = ServerKey("e", "p", "ruff")

    private fun diag(line: Int, severity: Int?, message: String, source: String? = null): Diagnostic {
        val sev = severity?.let { ""","severity": $it""" }.orEmpty()
        val src = source?.let { ""","source": "$it"""" }.orEmpty()
        return requireNotNull(
            Diagnostic.fromJson(
                Json.parseToJsonElement(
                    """{"range": {"start": {"line": $line, "character": 2}, "end": {"line": $line, "character": 5}}, "message": "$message"$sev$src}""",
                ),
            ),
        )
    }

    /** Project files map; `outside` does not (nothing could open it). */
    private val locate: (String, Range) -> NavLocation? = { uri, range ->
        val path = uri.removePrefix("file:///workspace/")
        if (uri.contains("outside")) null else NavLocation(path, File(path), path, range)
    }

    private val store = mapOf(
        "file:///workspace/b.py" to mapOf(
            pyright to DiagnosticSet(3, listOf(diag(9, 2, "warn late"), diag(1, 1, "error early"), diag(4, null, "no severity"))),
            ruff to DiagnosticSet(null, listOf(diag(0, 4, "hint", source = "Ruff"))),
        ),
        "file:///workspace/a.py" to mapOf(pyright to DiagnosticSet(null, listOf(diag(0, 3, "info")))),
        "file:///outside/x.py" to mapOf(pyright to DiagnosticSet(null, listOf(diag(0, 1, "dropped")))),
    )

    @Test
    fun groupsByFileAndOrdersBySeverityThenPosition() {
        val groups = ProblemsModel.build(store, locate, MinSeverity.HINT, emptySet())
        assertEquals(listOf("a.py", "b.py"), groups.map { it.label })
        assertEquals(listOf("error early", "no severity", "warn late", "hint"), groups[1].problems.map { it.message })
        assertEquals(DiagnosticSeverity.ERROR, groups[1].problems[1].severity)
        assertEquals(ruff, groups[1].problems[3].server)
    }

    @Test
    fun settingsFiltersApply() {
        val warnings = ProblemsModel.build(store, locate, MinSeverity.WARNING, emptySet())
        assertEquals(listOf("b.py"), warnings.map { it.label })
        assertEquals(3, warnings.single().problems.size)
        val noRuff = ProblemsModel.build(store, locate, MinSeverity.HINT, setOf("Ruff"))
        assertEquals(3, noRuff.last().problems.size)
    }

    @Test
    fun panelTogglesAndCounts() {
        val groups = ProblemsModel.build(store, locate, MinSeverity.HINT, emptySet())
        assertEquals(ProblemCounts(errors = 2, warnings = 1, infos = 1, hints = 1), ProblemsModel.counts(groups))
        val errorsOnly = ProblemsModel.visible(groups, setOf(DiagnosticSeverity.ERROR))
        assertEquals(listOf("b.py"), errorsOnly.map { it.label })
        assertEquals(2, errorsOnly.single().problems.size)
    }
}
