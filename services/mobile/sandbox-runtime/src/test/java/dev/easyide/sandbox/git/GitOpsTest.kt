package dev.easyide.sandbox.git

import org.eclipse.jgit.lib.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Refs, tags, detached checkout, cherry-pick and range diffs on real temp repositories. */
class GitOpsTest {

    private lateinit var root: File
    private lateinit var repo: GitRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("gitops").toFile()
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

    private fun fileCommit(path: String, text: String, message: String): String {
        write(path, text)
        return commitAll(message)
    }

    private fun failureOf(block: () -> Unit): Exception {
        try {
            block()
        } catch (e: Exception) {
            return e
        }
        throw AssertionError("expected a failure")
    }

    private fun setRef(name: String, id: String) {
        val update = repo.repository.updateRef(name)
        update.setNewObjectId(repo.repository.resolve(id))
        update.forceUpdate()
    }

    @Test fun `refs are keyed by full id with the current branch first and tags last`() {
        val first = fileCommit("a.txt", "one\n", "first")
        val branch = repo.currentBranch()
        repo.createBranch("alpha", null, checkout = false)
        repo.createBranch("zeta", null, checkout = false)
        setRef("refs/remotes/origin/main", first)
        repo.repository.updateRef("refs/remotes/origin/HEAD").link("refs/remotes/origin/main")
        repo.createTag("v1.0", first, null, "Ada", "ada@example.com")
        val second = fileCommit("a.txt", "two\n", "second")
        repo.createTag("v2.0", second, "release two", "Ada", "ada@example.com")

        val refs = repo.refsByCommit()
        assertEquals(
            listOf(
                GitRef("alpha", GitRefKind.LOCAL, false),
                GitRef("zeta", GitRefKind.LOCAL, false),
                GitRef("origin/main", GitRefKind.REMOTE, false),
                GitRef("v1.0", GitRefKind.TAG, false),
            ),
            refs.getValue(first),
        )
        assertEquals(
            listOf(GitRef(branch, GitRefKind.LOCAL, true), GitRef("v2.0", GitRefKind.TAG, false)),
            refs.getValue(second),
        )
    }

    @Test fun `valid tag names are accepted and bad ones refused`() {
        assertTrue(isValidTagName("v1.0"))
        assertTrue(isValidTagName("release/1"))
        assertTrue(!isValidTagName(""))
        assertTrue(!isValidTagName("a b"))
        assertTrue(!isValidTagName("-x"))
        assertTrue(!isValidTagName("a..b"))
    }

    @Test fun `creating a tag twice or with a bad name is refused`() {
        val id = fileCommit("a.txt", "one\n", "first")
        repo.createTag("v1", id, null, "Ada", "ada@example.com")
        assertTrue(failureOf { repo.createTag("v1", id, null, "Ada", "ada@example.com") }.message!!.contains("already exists"))
        assertTrue(failureOf { repo.createTag("bad name", id, null, "Ada", "ada@example.com") } is IllegalArgumentException)
        assertTrue(failureOf { repo.createTag("v2", "nope", null, "Ada", "ada@example.com") } is IllegalArgumentException)
    }

    @Test fun `a message makes an annotated tag and a blank one a lightweight tag`() {
        val id = fileCommit("a.txt", "one\n", "first")
        repo.createTag("light", id, "  ", "Ada", "ada@example.com")
        repo.createTag("heavy", id, "notes", "Ada", "ada@example.com")
        val light = repo.repository.findRef("refs/tags/light")
        val heavy = repo.repository.findRef("refs/tags/heavy")
        assertNull(repo.repository.refDatabase.peel(light).peeledObjectId)
        assertEquals(id, repo.repository.refDatabase.peel(heavy).peeledObjectId.name)
    }

    @Test fun `checkout detached moves head to the commit`() {
        val first = fileCommit("a.txt", "one\n", "first")
        fileCommit("a.txt", "two\n", "second")
        repo.checkoutDetached(first)
        assertTrue(repo.status().detached)
        assertEquals(first, repo.repository.resolve(Constants.HEAD).name)
        assertEquals("one\n", File(root, "a.txt").readText())
    }

    @Test fun `cherry pick applies a commit from another branch`() {
        fileCommit("a.txt", "one\n", "first")
        val main = repo.currentBranch()
        repo.createBranch("feature", null, checkout = true)
        val picked = fileCommit("b.txt", "bee\n", "add b")
        repo.switchBranch(main)
        val head = repo.cherryPick(picked, "Bob", "bob@example.com")
        val log = repo.log()
        assertEquals(head, log.first().id)
        assertEquals("add b", log.first().subject)
        assertEquals("bee\n", File(root, "b.txt").readText())
        assertTrue(head != picked)
    }

    @Test fun `cherry pick with a conflict names the files and leaves the repository conflicted`() {
        fileCommit("a.txt", "one\n", "first")
        val main = repo.currentBranch()
        repo.createBranch("feature", null, checkout = true)
        val picked = fileCommit("a.txt", "feature\n", "feature edit")
        repo.switchBranch(main)
        fileCommit("a.txt", "main\n", "main edit")
        val error = failureOf { repo.cherryPick(picked, "Bob", "bob@example.com") }
        assertEquals("Cherry-pick has conflicts in: a.txt", error.message)
        assertEquals(listOf("a.txt"), repo.status().conflicting.map { it.path })
    }

    @Test fun `cherry pick refuses a merge commit`() {
        fileCommit("a.txt", "one\n", "first")
        val main = repo.currentBranch()
        repo.createBranch("feature", null, checkout = true)
        fileCommit("b.txt", "bee\n", "add b")
        repo.switchBranch(main)
        fileCommit("c.txt", "sea\n", "add c")
        repo.git.merge().include(repo.repository.resolve("feature")).setCommit(true).call()
        val merge = repo.repository.resolve("HEAD").name
        repo.checkoutDetached("HEAD~1")
        assertTrue(failureOf { repo.cherryPick(merge, "Bob", "bob@example.com") }.message!!.contains("merge commit"))
    }

    @Test fun `merge base of two diverged branches is their fork point`() {
        val fork = fileCommit("a.txt", "one\n", "first")
        val main = repo.currentBranch()
        repo.createBranch("feature", null, checkout = true)
        fileCommit("b.txt", "bee\n", "add b")
        repo.switchBranch(main)
        fileCommit("c.txt", "sea\n", "add c")
        assertEquals(fork, repo.mergeBase(main, "feature"))
        assertNull(repo.mergeBase(main, "nope"))
    }

    @Test fun `changed between matches the commit detail files`() {
        fileCommit("a.txt", "one\ntwo\n", "first")
        fileCommit("b.txt", "keep\n", "second")
        write("a.txt", "one\n2\nthree\n")
        File(root, "b.txt").delete()
        write("c.txt", "new\n")
        val third = commitAll("third")
        val expected = repo.commitDetail(third)!!.files
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), expected.map { it.path })
        assertEquals(expected, repo.changedBetween("$third~1", third))
    }
}
