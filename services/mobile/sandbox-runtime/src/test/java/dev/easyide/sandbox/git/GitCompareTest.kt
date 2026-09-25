package dev.easyide.sandbox.git

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Comparisons between arbitrary ends and the commit detail, on real repositories. */
class GitCompareTest {

    private lateinit var root: File
    private lateinit var repo: GitRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("gitcompare").toFile()
        repo = GitRepository.init(root)
    }

    @After fun tearDown() {
        repo.close()
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun commitAll(message: String): String {
        repo.stage(repo.status().let { s -> (s.unstaged + s.staged).map { it.path } })
        return repo.commit(message, "Ada", "ada@example.com")
    }

    private fun text(diff: FileDiff) = diff as FileDiff.Text

    @Test fun `two revisions compare their committed content`() {
        write("a.txt", "one\ntwo\nthree\n")
        commitAll("first")
        write("a.txt", "one\n2\nthree\n")
        commitAll("second")
        val diff = text(repo.fileDiff("a.txt", DiffEnd.Rev("HEAD~1"), DiffEnd.Rev("HEAD")))
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
    }

    @Test fun `a revision against the working tree sees staged and unstaged edits together`() {
        write("a.txt", "one\ntwo\n")
        commitAll("first")
        write("a.txt", "one\ntwo\nthree\n")
        repo.stage(listOf("a.txt"))
        write("a.txt", "one\ntwo\nthree\nfour\n")
        assertEquals(2, text(repo.fileDiff("a.txt", DiffEnd.Rev("HEAD"), DiffEnd.Worktree)).added)
        assertEquals(1, text(repo.fileDiff("a.txt", DiffEnd.Rev("HEAD"), DiffEnd.Index)).added)
        assertEquals(1, text(repo.fileDiff("a.txt", DiffEnd.Index, DiffEnd.Worktree)).added)
    }

    @Test fun `the named sources are their pairs of ends`() {
        write("a.txt", "one\n")
        commitAll("first")
        write("a.txt", "one\ntwo\n")
        assertEquals(repo.fileDiff("a.txt", DiffEnd.Index, DiffEnd.Worktree), repo.fileDiff("a.txt", DiffSource.UNSTAGED))
        assertEquals(repo.fileDiff("a.txt", DiffEnd.Rev("HEAD"), DiffEnd.Index), repo.fileDiff("a.txt", DiffSource.STAGED))
    }

    @Test fun `nothing against a revision shows every line added`() {
        write("a.txt", "one\ntwo\n")
        val id = commitAll("first")
        assertEquals(2, text(repo.fileDiff("a.txt", DiffEnd.Empty, DiffEnd.Rev(id))).added)
    }

    @Test fun `an unknown revision is refused but an unborn HEAD is empty`() {
        write("a.txt", "one\n")
        repo.stage(listOf("a.txt"))
        assertEquals(1, text(repo.fileDiff("a.txt", DiffEnd.Rev("HEAD"), DiffEnd.Index)).added)
        try {
            repo.fileDiff("a.txt", DiffEnd.Rev("nope"), DiffEnd.Index)
            fail("expected the unknown revision to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("nope"))
        }
    }

    @Test fun `a commit lists its files with counts against its first parent`() {
        write("a.txt", "one\ntwo\n")
        write("dir/b.txt", "b\n")
        val first = commitAll("first")
        write("a.txt", "one\n")
        write("dir/c.txt", "c1\nc2\n")
        File(root, "dir/b.txt").delete()
        val second = commitAll("second\n\nbody")

        val root1 = repo.commitDetail(first)!!
        assertEquals(DiffEnd.Empty, root1.base)
        assertEquals(listOf("a.txt", "dir/b.txt"), root1.files.map { it.path })

        val detail = repo.commitDetail(second)!!
        assertEquals(DiffEnd.Rev(first), detail.base)
        assertEquals("second", detail.commit.subject)
        assertEquals(
            listOf(
                GitCommitFile("a.txt", GitChangeType.MODIFIED, 0, 1),
                GitCommitFile("dir/b.txt", GitChangeType.DELETED, 0, 1),
                GitCommitFile("dir/c.txt", GitChangeType.ADDED, 2, 0),
            ),
            detail.files,
        )
    }

    @Test fun `a revision that names nothing has no detail`() {
        write("a.txt", "one\n")
        commitAll("first")
        assertNull(repo.commitDetail("deadbeef"))
    }
}
