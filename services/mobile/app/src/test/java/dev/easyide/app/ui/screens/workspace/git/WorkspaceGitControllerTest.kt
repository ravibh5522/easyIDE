package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.WorkspaceGitController
import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.app.ui.shell.diff.DiffOutcome
import dev.easyide.app.ui.shell.diff.HunkAction
import dev.easyide.sandbox.git.DiffEnd
import dev.easyide.sandbox.git.FileDiff
import dev.easyide.sandbox.git.GitFailureKind
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitRepository
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.PullStrategy
import dev.easyide.sandbox.git.addRemote
import dev.easyide.sandbox.git.branchInfos
import dev.easyide.sandbox.git.createBranch
import dev.easyide.sandbox.git.switchBranch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The controllers against a real repository in a temp dir, with the settings
 * layer and guest-git runner faked. Guest git itself cannot run here (it needs
 * the on-device sandbox), so what is proven is what the app asks it to do and
 * how the app reacts to each outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceGitControllerTest {

    private class FakeSettings(initial: GitSettingsValues) : GitSettingsPort {
        val state = MutableStateFlow(initial)
        override val values: Flow<GitSettingsValues> = state
        val saved = mutableListOf<Pair<GitIdentity, Boolean>>()
        override suspend fun saveIdentity(identity: GitIdentity, projectOnly: Boolean): Boolean {
            saved += identity to projectOnly
            state.value = state.value.copy(userName = identity.name, userEmail = identity.email)
            return true
        }
    }

    private class Ran(val op: GitNetworkOp, val url: String?)

    private lateinit var root: File
    private lateinit var work: File
    private lateinit var repo: GitRepository
    private val ran = mutableListOf<Ran>()
    private var networkResult: suspend () -> GitResult<String> = { GitResult.Success("") }
    private val tokensSaved = mutableListOf<Triple<String, String, String>>()

    private val unset = GitSettingsValues("", "", PullStrategy.FF_ONLY, autoFetch = false)
    private val ada = GitSettingsValues("Ada", "ada@example.com", PullStrategy.REBASE, autoFetch = false)

    @Before fun setUp() {
        root = Files.createTempDirectory("ctl").toFile()
        work = File(root, "work").also { it.mkdirs() }
        repo = GitRepository.init(work)
    }

    @After fun tearDown() {
        repo.close()
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(work, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun baseCommit() {
        write("a.txt", "one\n")
        repo.stage(listOf("a.txt"))
        repo.commit("first", "Ada", "ada@example.com")
    }

    private fun TestScope.controller(settings: FakeSettings): WorkspaceGitController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WorkspaceGitController(
            gitService = GitService(dispatcher),
            projectRoot = work,
            scope = backgroundScope as CoroutineScope,
            network = { op, _, url, out ->
                ran += Ran(op, url)
                out("progress line")
                networkResult()
            },
            settings = settings,
            tokens = { host, user, token -> tokensSaved.add(Triple(host, user, token)) },
            foreground = MutableStateFlow(true),
        ).also {
            it.refresh()
        }
    }

    private fun WorkspaceGitController.now(): GitPanelState = state.value

    @Test fun `committing without an identity asks instead of inventing one`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        write("b.txt", "b\n")
        val settings = FakeSettings(unset)
        val ctl = controller(settings)
        ctl.stage(listOf("b.txt")); advanceUntilIdle()
        ctl.onMessageChanged("second"); advanceUntilIdle()

        ctl.commit(); advanceUntilIdle()

        assertTrue(ctl.now().identityPrompt)
        assertEquals(1, repo.log().size)
    }

    @Test fun `saving the identity stores it and finishes the commit with it`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        write("b.txt", "b\n")
        val settings = FakeSettings(unset)
        val ctl = controller(settings)
        ctl.stage(listOf("b.txt")); advanceUntilIdle()
        ctl.onMessageChanged("second"); advanceUntilIdle()
        ctl.commit(); advanceUntilIdle()

        ctl.saveIdentity("Grace Hopper", "grace@example.com", projectOnly = true); advanceUntilIdle()

        assertEquals(listOf(GitIdentity("Grace Hopper", "grace@example.com") to true), settings.saved)
        val head = repo.log().first()
        assertEquals("second", head.subject)
        assertEquals("Grace Hopper", head.authorName)
        assertEquals("grace@example.com", head.authorEmail)
        assertEquals("", ctl.now().commitMessage)
        assertTrue(!ctl.now().identityPrompt)
    }

    @Test fun `a malformed identity is not saved`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        val settings = FakeSettings(unset)
        val ctl = controller(settings)
        ctl.saveIdentity("Grace", "no-at-sign", projectOnly = false); advanceUntilIdle()
        assertTrue(settings.saved.isEmpty())
    }

    @Test fun `the commit draft survives a new controller`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        val first = controller(FakeSettings(ada))
        first.onMessageChanged("work in progr"); advanceUntilIdle()
        first.onMessageChanged("work in progress")
        // The save is a delayed background job, which advanceUntilIdle deliberately skips.
        advanceTimeBy(GitDefaults.DRAFT_SAVE_DELAY_MS + 1); runCurrent()

        val second = controller(FakeSettings(ada))
        advanceUntilIdle()
        assertEquals("work in progress", second.now().commitMessage)
    }

    private fun withPushedRemote(): File {
        val bare = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        repo.addRemote("origin", bare.absolutePath)
        // JGit directly: GitRepository keeps its handle internal to the sandbox module.
        Git.open(work).use { git ->
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            git.fetch().setRemote("origin").call()
            git.repository.config.apply {
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
        }
        return bare
    }

    @Test fun `amending a pushed commit needs confirmation`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        withPushedRemote()
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        ctl.setAmend(true); advanceUntilIdle()
        assertEquals("first", ctl.now().commitMessage)

        ctl.commit(); advanceUntilIdle()
        assertEquals(GitConfirm.AmendPushed, ctl.now().confirm)
        assertEquals("first", repo.log().single().subject)

        ctl.onMessageChanged("first, reworded"); advanceUntilIdle()
        ctl.answerConfirm(true); advanceUntilIdle()
        assertEquals("first, reworded", repo.log().single().subject)
        assertTrue(!ctl.now().amend)
    }

    @Test fun `amending an unpushed commit goes straight through`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        ctl.setAmend(true); advanceUntilIdle()
        ctl.onMessageChanged("reworded"); advanceUntilIdle()
        ctl.commit(); advanceUntilIdle()
        assertNull(ctl.now().confirm)
        assertEquals("reworded", repo.log().single().subject)
    }

    @Test fun `pull uses the strategy and identity from settings`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        withPushedRemote()
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()

        ctl.controllers.remote.pull(); advanceUntilIdle()

        assertEquals(GitNetworkOp.Pull(PullStrategy.REBASE, GitIdentity("Ada", "ada@example.com")), ran.single().op)
        val op = ctl.now().operation!!
        assertEquals(OperationStatus.SUCCEEDED, op.status)
        assertEquals(listOf("progress line"), op.output)
    }

    @Test fun `an auth failure names the host and saving a token retries`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        repo.addRemote("origin", "https://github.com/a/b.git")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        networkResult = { GitResult.Failure("fatal: Authentication failed", GitFailureKind.AUTH) }

        ctl.controllers.remote.fetch(); advanceUntilIdle()
        val failed = ctl.now().operation!!
        assertEquals(OperationStatus.FAILED, failed.status)
        assertEquals(GitFailureKind.AUTH, failed.failure)
        assertEquals("github.com", failed.authHost)

        networkResult = { GitResult.Success("") }
        assertTrue(ctl.controllers.remote.saveToken("github.com", "ada", "ghp_x")); advanceUntilIdle()
        assertEquals(listOf(Triple("github.com", "ada", "ghp_x")), tokensSaved)
        assertEquals(2, ran.size)
        assertEquals(OperationStatus.SUCCEEDED, ctl.now().operation!!.status)
    }

    @Test fun `cancelling stops the operation and keeps the output`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        repo.addRemote("origin", "https://github.com/a/b.git")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        networkResult = { awaitCancellation() }

        ctl.controllers.remote.fetch(); advanceUntilIdle()
        assertEquals(OperationStatus.RUNNING, ctl.now().operation!!.status)
        ctl.controllers.remote.cancel(); advanceUntilIdle()

        val op = ctl.now().operation!!
        assertEquals(OperationStatus.CANCELLED, op.status)
        assertEquals(listOf("progress line"), op.output)
    }

    @Test fun `a second operation is ignored while one runs`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        repo.addRemote("origin", "https://github.com/a/b.git")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        networkResult = { awaitCancellation() }
        ctl.controllers.remote.fetch()
        ctl.controllers.remote.fetch(); advanceUntilIdle()
        assertEquals(1, ran.size)
    }

    @Test fun `switching with edits asks and stash-and-switch keeps them`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        repo.createBranch("other", null, checkout = false)
        write("a.txt", "edited\n")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()

        ctl.controllers.branches.switchTo("other"); advanceUntilIdle()
        assertEquals(GitConfirm.SwitchDirty("other"), ctl.now().confirm)
        assertEquals("main", repo.currentBranch())

        ctl.answerConfirm(true); advanceUntilIdle()
        assertEquals("other", repo.currentBranch())
        assertEquals("one\n", File(work, "a.txt").readText())
        assertEquals(1, ctl.now().stashes.size)
        ctl.controllers.branches.popStash(0); advanceUntilIdle()
        assertEquals("edited\n", File(work, "a.txt").readText())
    }

    @Test fun `deleting an unmerged branch asks twice`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        repo.createBranch("wip", null, checkout = true)
        write("a.txt", "wip\n"); repo.stage(listOf("a.txt")); repo.commit("wip", "Ada", "ada@example.com")
        repo.switchBranch("main")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()

        ctl.controllers.branches.requestDelete("wip")
        assertEquals(GitConfirm.DeleteBranch("wip", unmerged = false), ctl.now().confirm)
        ctl.answerConfirm(true); advanceUntilIdle()
        assertEquals(GitConfirm.DeleteBranch("wip", unmerged = true), ctl.now().confirm)
        assertNull(ctl.now().error)

        ctl.answerConfirm(true); advanceUntilIdle()
        assertTrue(repo.branchInfos().none { it.name == "wip" })
    }

    @Test fun `discarding a file asks first`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        write("a.txt", "edited\n")
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()

        ctl.discard(listOf("a.txt")); advanceUntilIdle()
        assertEquals("edited\n", File(work, "a.txt").readText())
        ctl.answerConfirm(false)
        assertEquals("edited\n", File(work, "a.txt").readText())

        ctl.discard(listOf("a.txt")); advanceUntilIdle()
        ctl.answerConfirm(true); advanceUntilIdle()
        assertEquals("one\n", File(work, "a.txt").readText())
    }

    @Test fun `hunk staging through a diff document updates the diff and the status`() = runTest(UnconfinedTestDispatcher()) {
        val numbered = (1..12).joinToString("\n", postfix = "\n") { "l$it" }
        write("f.txt", numbered); repo.stage(listOf("f.txt")); repo.commit("base", "Ada", "ada@example.com")
        write("f.txt", numbered.replace("l2\n", "L2\n").replace("l11\n", "l11\nadded\n"))
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()

        val host = ctl.controllers.diff.host
        val uri = Comparison.unstaged("f.txt").uri!!
        val provider = host.providers.forUri(uri)!!
        suspend fun hunks() = ((provider.load(uri) as DiffOutcome.Ready).diff as FileDiff.Text).hunks
        assertEquals(2, hunks().size)
        assertEquals(listOf(HunkAction.STAGE, HunkAction.DISCARD), provider.actions(uri)!!.available)

        val revision = host.revision.value
        provider.actions(uri)!!.perform(HunkAction.STAGE, hunks()[0]); advanceUntilIdle()
        assertEquals(1, hunks().size)
        assertEquals(listOf("f.txt"), ctl.now().status!!.staged.map { it.path })
        assertTrue("a published status makes open diffs re-read", host.revision.value > revision)

        provider.actions(uri)!!.perform(HunkAction.DISCARD, hunks()[0])
        assertNotNull(ctl.now().confirm)
        ctl.answerConfirm(true); advanceUntilIdle()
        assertEquals(0, hunks().size)
        assertEquals(numbered.replace("l2\n", "L2\n"), File(work, "f.txt").readText())
    }

    @Test fun `the staged comparison offers unstaging and an arbitrary pair is read only`() = runTest(UnconfinedTestDispatcher()) {
        baseCommit()
        val ctl = controller(FakeSettings(ada))
        advanceUntilIdle()
        val provider = ctl.controllers.diff.host.providers.forUri(Comparison.staged("a.txt").uri!!)!!

        assertEquals(listOf(HunkAction.UNSTAGE), provider.actions(Comparison.staged("a.txt").uri!!)!!.available)
        val pair = Comparison("a.txt", DiffEnd.Rev("HEAD"), DiffEnd.Worktree).uri!!
        assertNull(provider.actions(pair))
    }
}
