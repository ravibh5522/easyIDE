package dev.easyide.app.ui.screens.home

import dev.easyide.sandbox.git.GitSummary
import dev.easyide.sandbox.model.ProjectRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFactsTest {

    private fun item(id: String, name: String, opened: Long) = ProjectListItem(
        project = ProjectRecord(id, name, "env", createdAtEpochMs = 0, lastOpenedAtEpochMs = opened),
        environment = null,
        sharedWithCount = 0,
        meta = null,
    )

    // --- resume ---

    @Test fun `resume is the most recently opened project`() {
        val items = listOf(item("1", "notes", 10), item("2", "api", 30), item("3", "web", 20))
        assertEquals("2", items.resumeTarget()?.project?.id)
    }

    @Test fun `resume of a tie is the first by name, then id`() {
        val items = listOf(item("b", "zed", 5), item("a", "Alpha", 5), item("c", "alpha", 5))
        assertEquals("a", items.resumeTarget()?.project?.id)
    }

    @Test fun `no projects means nothing to resume`() {
        assertNull(emptyList<ProjectListItem>().resumeTarget())
    }

    // --- branch ---

    @Test fun `a dirty tree marks the branch with a star`() {
        assertEquals("main*", GitSummary("main", 3).branchLabel())
        assertEquals("main", GitSummary("main", 0).branchLabel())
    }

    // --- delete confirmation ---

    @Test fun `delete needs the name, ignoring case and edge spaces`() {
        assertTrue(deleteConfirmed("api-server", "api-server"))
        assertTrue(deleteConfirmed("  API-Server ", "api-server"))
        assertFalse(deleteConfirmed("api", "api-server"))
        assertFalse(deleteConfirmed("", "api-server"))
    }

    // --- process size ---

    @Test fun `sizes stay in MB below a gigabyte and switch to GB after`() {
        assertEquals(ProcessSize(212.0, SizeUnit.MB), processSize(212 * 1024L))
        assertEquals(ProcessSize(0.0, SizeUnit.MB), processSize(0))
        val gb = processSize(1536 * 1024L)
        assertEquals(SizeUnit.GB, gb.unit)
        assertEquals(1.5, gb.value, 1e-9)
    }

    @Test fun `exactly one gigabyte is GB`() {
        assertEquals(ProcessSize(1.0, SizeUnit.GB), processSize(1024 * 1024L))
    }
}
