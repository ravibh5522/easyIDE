package dev.easyide.app.extensions.wasm

import dev.easyide.app.ui.screens.workspace.ext.TabSnapshot
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceEventDiff
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tab state -> `workspace.*` events for WASM subscribers. */
class WorkspaceEventDiffTest {

    private fun tab(content: String, saved: String = content, path: String = "/workspace/a.py") = TabSnapshot(path, "python", content, saved)

    @Test fun `open, change, save and close become events with rising versions`() {
        val diff = WorkspaceEventDiff()
        val opened = mapOf("/workspace/a.py" to tab("x"))
        assertEquals(listOf("""workspace.didOpen {"path":"/workspace/a.py","languageId":"python","version":1}"""), render(diff.diff(emptyMap(), opened)))
        val typed = mapOf("/workspace/a.py" to tab("xy", saved = "x"))
        assertEquals(listOf("""workspace.didChange {"path":"/workspace/a.py","version":2}"""), render(diff.diff(opened, typed)))
        assertEquals(2, diff.version("/workspace/a.py"))
        val saved = mapOf("/workspace/a.py" to TabSnapshot("/workspace/a.py", "python", typed.getValue("/workspace/a.py").content, String("xy".toCharArray())))
        assertEquals(listOf("""workspace.didSave {"path":"/workspace/a.py"}"""), render(diff.diff(typed, saved)))
        assertEquals(listOf("""workspace.didClose {"path":"/workspace/a.py"}"""), render(diff.diff(saved, emptyMap())))
        assertEquals(0, diff.version("/workspace/a.py"))
    }

    @Test fun `an unchanged snapshot produces nothing`() {
        val diff = WorkspaceEventDiff()
        val s = mapOf("/workspace/a.py" to tab("x"))
        diff.diff(emptyMap(), s)
        assertEquals(emptyList<String>(), render(diff.diff(s, s.toMap())))
    }

    private fun render(events: List<Pair<String, kotlinx.serialization.json.JsonObject>>) = events.map { (e, d) -> "$e $d" }
}
