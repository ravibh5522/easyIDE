package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.WorkspaceGitController
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitRefKind
import dev.easyide.sandbox.git.GitRepository
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.PullStrategy
import dev.easyide.sandbox.git.createBranch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The commit split button's follow-up steps and the graph menu's actions, against a real repository and a fake network. */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHistoryControllerTest {
    private class Settings(initial: GitSettingsValues) : GitSettingsPort {
        override val values: Flow<GitSettingsValues> = MutableStateFlow(initial)
        override suspend fun saveIdentity(identity: GitIdentity, projectOnly: Boolean) = true
    }

    private lateinit var root: File
    private lateinit var work: File
    private lateinit var repo: GitRepository
    private val ran = mutableListOf<GitNetworkOp>()
    private var networkResult: () -> GitResult<String> = { GitResult.Success("") }

    private val ada = GitSettingsValues("Ada", "ada@example.com", PullStrategy.REBASE, autoFetch = false)
    private val unset = GitSettingsValues("", "", PullStrategy.FF_ONLY, autoFetch = false)

    @Before fun setUp() {
        root = Files.createTempDirectory("hist").toFile()
        work = File(root, "work").also { it.mkdirs() }
        repo = GitRepository.init(work)
    }

    @After fun tearDown() {
        repo.close()
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(work, path).writeText(text)

    private fun commitFile(path: String, text: String, message: String): String {
        write(path, text)
        repo.stage(listOf(path))
        return repo.commit(message, "Ada", "ada@example.com")
    }

    /** `main` follows `origin/main`, so a push is a plain push and a pull has something to talk to. */
    private fun track() {
        Git.open(work).use { git ->
            val config = git.repository.config
            config.setString("remote", "origin", "url", "https://example.com/a/b.git")
            config.setString("branch", "main", "remote", "origin")
            config.setString("branch", "main", "merge", "refs/heads/main")
            config.save()
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(git.repository.resolve("HEAD")); update() }
        }
    }

    private fun TestScope.controller(settings: GitSettingsValues): WorkspaceGitController =
        WorkspaceGitController(
            gitService = GitService(UnconfinedTestDispatcher(testScheduler)),
            projectRoot = work,
            scope = backgroundScope as CoroutineScope,
            network = { op, _, _, _ -> ran += op; networkResult() },
            settings = Settings(settings),
            tokens = { _, _, _ -> true },
            foreground = MutableStateFlow(true),
        ).also { it.refresh() }

    private fun WorkspaceGitController.now(): GitPanelState = state.value

    private fun WorkspaceGitController.stageAndType(path: String, text: String, message: String) {
        write(path, text)
        stage(listOf(path))
        onMessageChanged(message)
    }

    @Test fun `commit and push commits, then pushes`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first"); track()
        val ctl = controller(ada); advanceUntilIdle()
        ctl.stageAndType("b.txt", "b\n", "second"); advanceUntilIdle()

        ctl.commit(CommitMode.COMMIT_PUSH); advanceUntilIdle()

        assertEquals(2, repo.log().size)
        assertEquals(listOf(GitNetworkOp.Push(null, null)), ran)
    }

    @Test fun `commit and sync pulls with the configured strategy, then pushes`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first"); track()
        val ctl = controller(ada); advanceUntilIdle()
        ctl.stageAndType("b.txt", "b\n", "second"); advanceUntilIdle()

        ctl.commit(CommitMode.COMMIT_SYNC); advanceUntilIdle()

        assertEquals(listOf<Class<*>>(GitNetworkOp.Pull::class.java, GitNetworkOp.Push::class.java), ran.map { it::class.java })
        assertEquals(PullStrategy.REBASE, (ran[0] as GitNetworkOp.Pull).strategy)
    }

    @Test fun `a failed pull stops the sync before the push`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first"); track()
        val ctl = controller(ada); advanceUntilIdle()
        networkResult = { GitResult.Failure("diverged") }

        ctl.controllers.remote.sync(); advanceUntilIdle()

        assertEquals(1, ran.size)
        assertTrue(ran.single() is GitNetworkOp.Pull)
    }

    @Test fun `sync on a branch with no upstream only publishes it`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first")
        Git.open(work).use { it.repository.config.apply { setString("remote", "origin", "url", "https://example.com/a/b.git"); save() } }
        val ctl = controller(ada); advanceUntilIdle()

        ctl.controllers.remote.sync(); advanceUntilIdle()

        assertEquals(listOf(GitNetworkOp.Push("origin", "main", setUpstream = true)), ran)
    }

    @Test fun `commit amend rewrites HEAD with its own message when the box is empty`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first")
        val ctl = controller(ada); advanceUntilIdle()
        write("a.txt", "2\n"); ctl.stage(listOf("a.txt")); advanceUntilIdle()

        ctl.commit(CommitMode.AMEND); advanceUntilIdle()

        assertEquals(listOf("first"), repo.log().map { it.subject })
        assertFalse(ctl.now().amend)
    }

    @Test fun `a plain commit after amend was picked does not amend`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first")
        val ctl = controller(ada); advanceUntilIdle()
        ctl.setAmend(true); advanceUntilIdle()
        ctl.stageAndType("b.txt", "b\n", "second"); advanceUntilIdle()

        ctl.commit(CommitMode.COMMIT); advanceUntilIdle()

        assertEquals(2, repo.log().size)
    }

    @Test fun `the panel carries the refs of every commit`() = runTest(UnconfinedTestDispatcher()) {
        val first = commitFile("a.txt", "1\n", "first"); track()
        repo.createBranch("topic", null, checkout = false)
        val ctl = controller(ada); advanceUntilIdle()

        val refs = ctl.now().refs.getValue(first)
        assertEquals(listOf("main", "topic", "origin/main"), refs.map { it.name })
        assertEquals(listOf(GitRefKind.LOCAL, GitRefKind.LOCAL, GitRefKind.REMOTE), refs.map { it.kind })
        assertTrue(ctl.now().branches.any { it.name == "topic" })
    }

    @Test fun `tagging a commit creates the tag and cherry-picking applies it`() = runTest(UnconfinedTestDispatcher()) {
        val first = commitFile("a.txt", "1\n", "first")
        repo.createBranch("side", null, checkout = true)
        val picked = commitFile("s.txt", "s\n", "side work")
        Git.open(work).use { it.checkout().setName("main").call() }
        val ctl = controller(ada); advanceUntilIdle()

        ctl.controllers.history.createTag(first, "v1", "release one"); advanceUntilIdle()
        assertTrue(ctl.now().refs.getValue(first).any { it.name == "v1" && it.kind == GitRefKind.TAG })

        ctl.controllers.history.cherryPick(picked); advanceUntilIdle()
        assertEquals(listOf("side work", "first"), repo.log().map { it.subject })
    }

    @Test fun `tag and cherry-pick ask for an identity instead of inventing one`() = runTest(UnconfinedTestDispatcher()) {
        val first = commitFile("a.txt", "1\n", "first")
        val ctl = controller(unset); advanceUntilIdle()

        ctl.controllers.history.createTag(first, "v1", ""); advanceUntilIdle()

        assertTrue(ctl.now().identityRequired)
        assertTrue(ctl.now().refs.getValue(first).none { it.kind == GitRefKind.TAG })
    }

    @Test fun `checkout detached moves HEAD off the branch`() = runTest(UnconfinedTestDispatcher()) {
        val first = commitFile("a.txt", "1\n", "first")
        commitFile("a.txt", "2\n", "second")
        val ctl = controller(ada); advanceUntilIdle()

        ctl.controllers.history.checkoutDetached(first); advanceUntilIdle()

        assertTrue(ctl.now().status!!.detached)
    }

    @Test fun `compare with merge base lists what the commit changed since the fork`() = runTest(UnconfinedTestDispatcher()) {
        commitFile("a.txt", "1\n", "first")
        repo.createBranch("side", null, checkout = true)
        val tip = commitFile("s.txt", "s\n", "side work")
        Git.open(work).use { it.checkout().setName("main").call() }
        commitFile("m.txt", "m\n", "main work")
        val ctl = controller(ada); advanceUntilIdle()

        ctl.controllers.history.compareWithMergeBase(tip, "side"); advanceUntilIdle()

        val comparison = ctl.now().comparison
        assertNotNull(comparison)
        assertEquals(listOf("s.txt"), comparison!!.files.map { it.path })
        ctl.controllers.history.closeComparison()
        assertNull(ctl.now().comparison)
    }

    @Test fun `compare with remote needs an upstream`() = runTest(UnconfinedTestDispatcher()) {
        val first = commitFile("a.txt", "1\n", "first")
        val ctl = controller(ada); advanceUntilIdle()

        ctl.controllers.history.compareWithRemote(first, "first"); advanceUntilIdle()

        assertNull(ctl.now().comparison)
    }
}
