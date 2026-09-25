package dev.easyide.sandbox.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real repositories in temp dirs: these are the JGit behaviours the panel depends on. */
class GitRepositoryTest {

    private lateinit var root: File
    private lateinit var work: File
    private lateinit var repo: GitRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("gitrepo").toFile()
        work = File(root, "work").also { it.mkdirs() }
        repo = GitRepository.init(work)
    }

    @After fun tearDown() {
        repo.close()
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(work, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun commitAll(message: String): String {
        repo.stage(repo.status().let { s -> (s.unstaged + s.staged).map { it.path } })
        return repo.commit(message, "Ada", "ada@example.com")
    }

    private val numbered = (1..12).joinToString("\n", postfix = "\n") { "l$it" }

    /** A bare remote with `main` pushed, and the work repo tracking it. */
    private fun withRemote(): File {
        write("a.txt", "one\n")
        commitAll("first")
        val bare = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        repo.addRemote("origin", bare.absolutePath)
        repo.git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
        repo.git.fetch().setRemote("origin").call()
        val config = repo.repository.config
        config.setString("branch", "main", "remote", "origin")
        config.setString("branch", "main", "merge", "refs/heads/main")
        config.save()
        return bare
    }

    @Test fun `status reports ahead and behind of the upstream`() {
        val bare = withRemote()
        assertEquals(0, repo.status().ahead)
        assertEquals("origin/main", repo.status().upstream)

        write("a.txt", "two\n")
        commitAll("local")
        assertEquals(1, repo.status().ahead)
        assertEquals(0, repo.status().behind)

        // Another clone pushes, then this one fetches: behind.
        val other = File(root, "other")
        Git.cloneRepository().setURI(bare.absolutePath).setDirectory(other).call().use { g ->
            File(other, "b.txt").writeText("b\n")
            g.add().addFilepattern("b.txt").call()
            g.commit().setMessage("remote work").setAuthor("Bob", "bob@example.com").call()
            g.push().call()
        }
        repo.git.fetch().setRemote("origin").call()
        val status = repo.status()
        assertEquals(1, status.ahead)
        assertEquals(1, status.behind)
    }

    @Test fun `branch without upstream has no counts`() {
        write("a.txt", "x\n"); commitAll("c")
        val status = repo.status()
        assertNull(status.upstream)
        assertEquals(0, status.ahead)
    }

    @Test fun `head pushed follows the remote tracking ref`() {
        withRemote()
        assertTrue(repo.isHeadPushed())
        write("a.txt", "two\n"); commitAll("local")
        assertFalse(repo.isHeadPushed())
    }

    @Test fun `amend rewrites head instead of adding a commit`() {
        write("a.txt", "one\n"); commitAll("first")
        write("a.txt", "two\n")
        repo.stage(listOf("a.txt"))
        repo.commit("first, better", "Ada", "ada@example.com", amend = true)
        val log = repo.log()
        assertEquals(1, log.size)
        assertEquals("first, better", log.single().subject)
    }

    @Test fun `remotes can be added listed and removed`() {
        repo.addRemote("origin", "https://example.com/a/b.git")
        assertEquals(listOf(GitRemoteInfo("origin", "https://example.com/a/b.git")), repo.remoteInfos())
        repo.removeRemote("origin")
        assertTrue(repo.remoteInfos().isEmpty())
        try {
            repo.addRemote("bad name", "https://example.com/a/b.git")
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun `branches create switch rename and delete`() {
        write("a.txt", "one\n"); commitAll("first")
        repo.createBranch("feature", null, checkout = false)
        assertEquals(listOf("feature", "main"), repo.branchInfos().map { it.name })
        repo.switchBranch("feature")
        assertEquals("feature", repo.currentBranch())
        assertTrue(repo.branchInfos().single { it.name == "feature" }.isCurrent)
        repo.switchBranch("main")
        repo.renameBranch("feature", "topic")
        assertEquals(listOf("main", "topic"), repo.branchInfos().map { it.name })
        repo.deleteBranch("topic", force = false)
        assertEquals(listOf("main"), repo.branchInfos().map { it.name })
    }

    @Test fun `invalid branch names are rejected before git sees them`() {
        write("a.txt", "one\n"); commitAll("first")
        assertFalse(isValidBranchName("has space"))
        assertFalse(isValidBranchName("-dash"))
        assertFalse(isValidBranchName(""))
        assertTrue(isValidBranchName("feat/ok-1"))
        try {
            repo.createBranch("a..b", null, checkout = false)
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun `deleting an unmerged branch needs force and the failure is typed`() {
        write("a.txt", "one\n"); commitAll("first")
        repo.createBranch("wip", null, checkout = true)
        write("a.txt", "wip\n"); commitAll("wip work")
        repo.switchBranch("main")
        val failure = runCatching { repo.deleteBranch("wip", force = false) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(GitFailureKind.UNMERGED_BRANCH, failureOf(failure!!).kind)
        repo.deleteBranch("wip", force = true)
        assertEquals(listOf("main"), repo.branchInfos().map { it.name })
    }

    @Test fun `switching with a conflicting edit is typed as a dirty tree`() {
        write("a.txt", "one\n"); commitAll("first")
        repo.createBranch("other", null, checkout = true)
        write("a.txt", "other\n"); commitAll("other change")
        repo.switchBranch("main")
        write("a.txt", "local edit\n")
        val failure = runCatching { repo.switchBranch("other") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(GitFailureKind.DIRTY_TREE, failureOf(failure!!).kind)
        assertTrue(repo.status().hasTrackedChanges)
    }

    @Test fun `a remote only branch is checked out as a tracking branch`() {
        val bare = withRemote()
        val other = File(root, "other")
        Git.cloneRepository().setURI(bare.absolutePath).setDirectory(other).call().use { g ->
            g.checkout().setCreateBranch(true).setName("feature").call()
            File(other, "f.txt").writeText("f\n")
            g.add().addFilepattern("f.txt").call()
            g.commit().setMessage("f").setAuthor("Bob", "bob@example.com").call()
            g.push().setRemote("origin").add("feature").call()
        }
        repo.git.fetch().setRemote("origin").call()
        assertTrue(repo.branchInfos().any { it.name == "origin/feature" && it.isRemote })
        repo.switchBranch("origin/feature")
        assertEquals("feature", repo.currentBranch())
        assertEquals("origin/feature", repo.branchInfos().single { it.name == "feature" }.upstream)
        assertTrue(File(work, "f.txt").exists())
    }

    @Test fun `stash shelves changes and pop restores them`() {
        write("a.txt", "one\n"); commitAll("first")
        write("a.txt", "edited\n")
        assertTrue(repo.stashPush("wip", includeUntracked = false))
        assertEquals("one\n", File(work, "a.txt").readText())
        assertEquals(1, repo.stashEntries().size)
        repo.stashPop(0)
        assertEquals("edited\n", File(work, "a.txt").readText())
        assertTrue(repo.stashEntries().isEmpty())
    }

    @Test fun `stash with nothing to stash reports false`() {
        write("a.txt", "one\n"); commitAll("first")
        assertFalse(repo.stashPush(null, includeUntracked = false))
    }

    @Test fun `stash drop removes the entry without touching the tree`() {
        write("a.txt", "one\n"); commitAll("first")
        write("a.txt", "edited\n")
        repo.stashPush(null, includeUntracked = false)
        repo.stashDrop(0)
        assertTrue(repo.stashEntries().isEmpty())
        assertEquals("one\n", File(work, "a.txt").readText())
    }

    private fun diffOf(path: String, source: DiffSource) = repo.fileDiff(path, source)

    @Test fun `unstaged diff of a modified file has its hunks`() {
        write("f.txt", numbered); commitAll("base")
        write("f.txt", numbered.replace("l2\n", "L2\n").replace("l11\n", "l11\nadded\n"))
        val diff = diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text
        assertEquals(2, diff.hunks.size)
        assertEquals(2, diff.added)
        assertEquals(1, diff.removed)
        assertTrue((diffOf("f.txt", DiffSource.STAGED) as FileDiff.Text).hunks.isEmpty())
    }

    @Test fun `untracked file diff is all additions`() {
        write("base.txt", "x\n"); commitAll("base")
        write("new.txt", "a\nb\n")
        val diff = diffOf("new.txt", DiffSource.UNSTAGED) as FileDiff.Text
        assertEquals(2, diff.added)
        assertEquals(0, diff.removed)
    }

    @Test fun `binary and oversized files are guarded`() {
        write("base.txt", "x\n"); commitAll("base")
        File(work, "img.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        assertTrue(diffOf("img.bin", DiffSource.UNSTAGED) is FileDiff.Binary)
        File(work, "big.txt").writeText("line\n".repeat((GitDiffLimits.MAX_TEXT_BYTES / 5 + 10).toInt()))
        val big = diffOf("big.txt", DiffSource.UNSTAGED)
        assertTrue(big is FileDiff.TooLarge)
    }

    @Test fun `staging one hunk leaves the other unstaged`() {
        write("f.txt", numbered); commitAll("base")
        val edited = numbered.replace("l2\n", "L2\n").replace("l11\n", "l11\nadded\n")
        write("f.txt", edited)
        val first = (diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text).hunks[0]
        repo.stageHunk("f.txt", first)

        assertEquals(1, (diffOf("f.txt", DiffSource.STAGED) as FileDiff.Text).hunks.size)
        assertEquals(1, (diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text).hunks.size)
        assertEquals(edited, File(work, "f.txt").readText())
        val status = repo.status()
        assertEquals(listOf("f.txt"), status.staged.map { it.path })
        assertEquals(listOf("f.txt"), status.unstaged.map { it.path })
    }

    @Test fun `unstaging a hunk restores the index but not the file`() {
        write("f.txt", numbered); commitAll("base")
        val edited = numbered.replace("l2\n", "L2\n")
        write("f.txt", edited)
        repo.stage(listOf("f.txt"))
        val staged = (diffOf("f.txt", DiffSource.STAGED) as FileDiff.Text).hunks[0]
        repo.unstageHunk("f.txt", staged)
        assertTrue(repo.status().staged.isEmpty())
        assertEquals(edited, File(work, "f.txt").readText())
    }

    @Test fun `discarding a hunk reverts only that hunk in the working tree`() {
        write("f.txt", numbered); commitAll("base")
        write("f.txt", numbered.replace("l2\n", "L2\n").replace("l11\n", "l11\nadded\n"))
        val second = (diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text).hunks[1]
        repo.discardHunk("f.txt", second)
        assertEquals(numbered.replace("l2\n", "L2\n"), File(work, "f.txt").readText())
    }

    @Test fun `staging a hunk of a deleted file removes it from the index`() {
        write("f.txt", "a\nb\n"); commitAll("base")
        File(work, "f.txt").delete()
        val hunk = (diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text).hunks.single()
        repo.stageHunk("f.txt", hunk)
        val status = repo.status()
        assertEquals(GitChangeType.DELETED, status.staged.single().type)
        assertTrue(status.unstaged.isEmpty())
    }

    @Test fun `hunk actions refuse a file that is not utf8`() {
        write("f.txt", "a\n"); commitAll("base")
        File(work, "f.txt").writeBytes(byteArrayOf('a'.code.toByte(), '\n'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte()))
        val hunk = (diffOf("f.txt", DiffSource.UNSTAGED) as FileDiff.Text).hunks.single()
        val failure = runCatching { repo.discardHunk("f.txt", hunk) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun `commit draft lives in the git dir and never shows as a change`() {
        write("a.txt", "one\n"); commitAll("first")
        assertEquals("", repo.readDraft())
        repo.writeDraft("half a message")
        assertEquals("half a message", repo.readDraft())
        assertTrue(repo.status().isClean)
        repo.writeDraft("  ")
        assertEquals("", repo.readDraft())
    }
}
