package dev.easyide.app.ui.screens.home

import dev.easyide.sandbox.git.GitSummary
import dev.easyide.sandbox.model.ProjectRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectListModelTest {

    private fun item(
        id: String,
        name: String,
        opened: Long = 0,
        branch: String? = null,
        folder: String? = null,
    ) = ProjectListItem(
        project = ProjectRecord(id, name, "env", createdAtEpochMs = 0, lastOpenedAtEpochMs = opened),
        environment = null,
        sharedWithCount = 0,
        meta = if (branch != null || folder != null) {
            ProjectMeta(branch?.let { GitSummary(it, 0) }, null, folder, loadedAtEpochMs = 0)
        } else {
            null
        },
    )

    private val items = listOf(
        item("1", "notes", opened = 10),
        item("2", "API-svc", opened = 30, branch = "main"),
        item("3", "web", opened = 20, branch = "feature-x", folder = "Documents"),
    )

    private fun ids(list: List<ProjectListItem>) = list.map { it.project.id }

    @Test fun `recent sorts newest first, ties by name then id`() {
        assertEquals(listOf("2", "3", "1"), ids(items.searchedAndSorted("", ProjectSort.RECENT)))
        val tied = listOf(item("b", "zed", 5), item("a", "Alpha", 5), item("c", "alpha", 5))
        assertEquals(listOf("a", "c", "b"), ids(tied.searchedAndSorted("", ProjectSort.RECENT)))
    }

    @Test fun `name sort ignores case`() {
        assertEquals(listOf("2", "1", "3"), ids(items.searchedAndSorted("", ProjectSort.NAME)))
    }

    @Test fun `search matches name, branch and folder case-insensitively`() {
        assertEquals(listOf("2"), ids(items.searchedAndSorted("api", ProjectSort.RECENT)))
        assertEquals(listOf("3"), ids(items.searchedAndSorted("FEATURE", ProjectSort.RECENT)))
        assertEquals(listOf("3"), ids(items.searchedAndSorted("docum", ProjectSort.RECENT)))
    }

    @Test fun `every word must match`() {
        assertEquals(listOf("2"), ids(items.searchedAndSorted("api main", ProjectSort.RECENT)))
        assertTrue(items.searchedAndSorted("api feature", ProjectSort.RECENT).isEmpty())
    }

    @Test fun `blank query keeps everything`() {
        assertEquals(3, items.searchedAndSorted("   ", ProjectSort.NAME).size)
    }

    @Test fun `selection survives, else falls back to the first row`() {
        val visible = items.searchedAndSorted("", ProjectSort.RECENT)
        assertEquals("3", selectionIn(visible, "3")?.project?.id)
        assertEquals("2", selectionIn(visible, "gone")?.project?.id)
        assertEquals("2", selectionIn(visible, null)?.project?.id)
        assertNull(selectionIn(emptyList(), "3"))
    }

    @Test fun `meta is fresh only while young and read after the project was opened`() {
        val project = ProjectRecord("p", "p", "env", 0, lastOpenedAtEpochMs = 1_000)
        fun meta(at: Long) = ProjectMeta(null, null, null, at)
        val now = 1_000 + ProjectMetaPolicy.FRESH_FOR_MS - 1

        assertTrue(ProjectMetaPolicy.isFresh(meta(1_000), project, now))
        assertFalse(ProjectMetaPolicy.isFresh(meta(1_000), project, 1_000 + ProjectMetaPolicy.FRESH_FOR_MS))
        // Loaded before the project was last opened: the workspace may have changed it since.
        assertFalse(ProjectMetaPolicy.isFresh(meta(999), project, 1_001))
        assertFalse(ProjectMetaPolicy.isFresh(null, project, 1_001))
        // A shorter max age, as used when returning to Home.
        assertFalse(ProjectMetaPolicy.isFresh(meta(1_000), project, 1_000 + ProjectMetaPolicy.RESUME_REFRESH_AFTER_MS, ProjectMetaPolicy.RESUME_REFRESH_AFTER_MS))
    }
}
