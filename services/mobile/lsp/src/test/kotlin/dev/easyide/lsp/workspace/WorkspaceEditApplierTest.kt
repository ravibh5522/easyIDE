package dev.easyide.lsp.workspace

import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.protocol.EditOperation
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.testing.RecordingEditPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceEditApplierTest {
    private val mapper = WorkspacePathMapper(File("/p"), File("/r"), "/workspace", listOf("/proc"))
    private val store = DocumentStore()
    private val port = RecordingEditPort()
    private val applier = WorkspaceEditApplier(mapper, store, port)
    private val edit = listOf(TextEdit(Range(Position(0, 0), Position(0, 1)), "x"))

    @Test
    fun appliesAllOperationsInOrder() = runBlocking {
        val result = applier.apply(
            WorkspaceEdit(listOf(
                EditOperation.Create("file:///workspace/new.py", overwrite = false, ignoreIfExists = true),
                EditOperation.Text("file:///workspace/a.py", null, edit),
                EditOperation.Rename("file:///workspace/b.py", "file:///workspace/c.py", overwrite = false, ignoreIfExists = false),
                EditOperation.Delete("file:///workspace/d.py", recursive = false, ignoreIfNotExists = true),
            )),
            "Rename",
        )
        assertEquals(ApplyResult(true), result)
        assertEquals(listOf("create new.py", "rename b.py c.py", "delete d.py"), port.operations)
        assertEquals("a.py", port.textEdits.single().first.projectRelative)
    }

    @Test
    fun readOnlyOrUnmappableTargetRefusesTheWholeEditUpFront() = runBlocking {
        val result = applier.apply(
            WorkspaceEdit(listOf(
                EditOperation.Text("file:///workspace/a.py", null, edit),
                EditOperation.Text("file:///usr/lib/python3/os.py", null, edit),
            )),
            "Fix",
        )
        assertFalse(result.applied)
        assertEquals(1, result.failedChange)
        assertTrue(port.textEdits.isEmpty())
        assertFalse(applier.apply(WorkspaceEdit(listOf(EditOperation.Text("untitled:1", null, edit))), "x").applied)
    }

    @Test
    fun versionMismatchAbortsAndEarlierOperationsStay() = runBlocking {
        store.open("file:///workspace/b.py", "python", "one")
        store.update("file:///workspace/b.py", "two")
        val result = applier.apply(
            WorkspaceEdit(listOf(
                EditOperation.Text("file:///workspace/a.py", null, edit),
                EditOperation.Text("file:///workspace/b.py", 1, edit),
                EditOperation.Text("file:///workspace/c.py", null, edit),
            )),
            "Format",
        )
        assertEquals(ApplyResult(false, "Document changed, try again", 1), result)
        assertEquals(listOf("a.py"), port.textEdits.map { it.first.projectRelative })
    }

    @Test
    fun portFailureAbortsAtThatOperation() = runBlocking {
        port.failing = "b.py"
        val result = applier.apply(
            WorkspaceEdit(listOf(
                EditOperation.Text("file:///workspace/b.py", null, edit),
                EditOperation.Text("file:///workspace/c.py", null, edit),
            )),
            "Fix",
        )
        assertEquals(0, result.failedChange)
        assertTrue(port.textEdits.isEmpty())
    }
}
