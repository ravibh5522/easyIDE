package dev.easyide.sandbox.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitSummaryTest {

    @get:Rule val temp = TemporaryFolder()

    private val service = GitService(Dispatchers.Unconfined)

    @Test fun `a directory without a repository is reported as such`() = runBlocking {
        assertEquals(GitResult.NotARepository, service.summary(temp.newFolder("plain")))
    }

    @Test fun `clean repository has a branch and no changes`() = runBlocking {
        val dir = temp.newFolder("repo")
        File(dir, "a.txt").writeText("a")
        GitRepository.init(dir).use {
            it.stage(listOf("a.txt"))
            it.commit("first", "Test", "test@example.com")
        }

        val summary = (service.summary(dir) as GitResult.Success).value

        assertEquals("main", summary.branch)
        assertFalse(summary.isDirty)
    }

    @Test fun `modified and untracked files count as changes`() = runBlocking {
        val dir = temp.newFolder("repo")
        File(dir, "a.txt").writeText("a")
        GitRepository.init(dir).use {
            it.stage(listOf("a.txt"))
            it.commit("first", "Test", "test@example.com")
        }
        File(dir, "a.txt").writeText("changed")
        File(dir, "new.txt").writeText("n")

        val summary = (service.summary(dir) as GitResult.Success).value

        assertEquals(2, summary.changedFiles)
        assertTrue(summary.isDirty)
    }
}
