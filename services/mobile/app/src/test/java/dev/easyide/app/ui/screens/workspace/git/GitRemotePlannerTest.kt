package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRemotePlannerTest {

    private val origin = GitRemoteInfo("origin", "https://github.com/a/b.git")
    private val fork = GitRemoteInfo("fork", "https://gitlab.com/me/b.git")

    private fun status(branch: String = "main", upstream: String? = null, detached: Boolean = false) =
        GitStatus(branch, emptyList(), emptyList(), emptyList(), true, upstream = upstream, detached = detached)

    @Test fun `the upstream's remote wins over origin`() {
        assertEquals(fork, GitRemotePlanner.defaultRemote("fork/main", listOf(origin, fork)))
    }

    @Test fun `origin is the default without an upstream`() {
        assertEquals(origin, GitRemotePlanner.defaultRemote(null, listOf(fork, origin)))
    }

    @Test fun `a lone remote is the default whatever its name`() {
        assertEquals(fork, GitRemotePlanner.defaultRemote(null, listOf(fork)))
    }

    @Test fun `several remotes and no origin means no default`() {
        val other = GitRemoteInfo("other", "https://example.com/x.git")
        assertNull(GitRemotePlanner.defaultRemote(null, listOf(fork, other)))
    }

    @Test fun `token lookup uses the url of the chosen remote`() {
        assertEquals(fork.url, GitRemotePlanner.urlFor(status(upstream = "fork/main"), listOf(origin, fork)))
    }

    @Test fun `a branch with an upstream pushes plainly`() {
        assertEquals(GitNetworkOp.Push(null, null), GitRemotePlanner.pushOp(status(upstream = "origin/main"), listOf(origin)))
    }

    @Test fun `a branch without one is published to the default remote`() {
        assertEquals(
            GitNetworkOp.Push("origin", "feat", setUpstream = true),
            GitRemotePlanner.pushOp(status("feat"), listOf(origin)),
        )
    }

    @Test fun `force is always with lease`() {
        val op = GitRemotePlanner.pushOp(status(upstream = "origin/main"), listOf(origin), forceWithLease = true)
        assertEquals(GitNetworkOp.Push(null, null, forceWithLease = true), op)
        assertEquals(GitOperationKind.FORCE_PUSH, GitRemotePlanner.kindOf(op!!))
    }

    @Test fun `nothing to push to`() {
        assertNull(GitRemotePlanner.pushOp(status(), emptyList()))
        assertNull(GitRemotePlanner.pushOp(status(detached = true), listOf(origin)))
    }

    @Test fun `operation kinds`() {
        assertEquals(GitOperationKind.PUBLISH, GitRemotePlanner.kindOf(GitNetworkOp.Push("origin", "x", setUpstream = true)))
        assertEquals(GitOperationKind.PUSH, GitRemotePlanner.kindOf(GitNetworkOp.Push(null, null)))
        assertEquals(GitOperationKind.FETCH, GitRemotePlanner.kindOf(GitNetworkOp.Fetch()))
    }
}
