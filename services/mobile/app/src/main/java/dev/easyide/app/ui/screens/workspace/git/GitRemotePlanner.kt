package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStatus

/**
 * Chooses which remote an operation talks to and what a push means for the
 * current branch. Pure, so the rules for "publish vs push" and "which token"
 * are tested rather than inferred from UI behaviour.
 */
object GitRemotePlanner {

    const val DEFAULT_REMOTE = "origin"

    /**
     * The remote the branch follows, else `origin`, else the only remote. With
     * several remotes and none of those, there is no safe default: null, and the
     * user adds an upstream by publishing explicitly.
     */
    fun defaultRemote(upstream: String?, remotes: List<GitRemoteInfo>): GitRemoteInfo? {
        upstream?.substringBefore('/')?.let { name -> remotes.firstOrNull { it.name == name }?.let { return it } }
        return remotes.firstOrNull { it.name == DEFAULT_REMOTE } ?: remotes.singleOrNull()
    }

    /** The URL whose host decides which stored token git is given. */
    fun urlFor(status: GitStatus?, remotes: List<GitRemoteInfo>): String? =
        defaultRemote(status?.upstream, remotes)?.url

    fun fetchOp(status: GitStatus?, remotes: List<GitRemoteInfo>): GitNetworkOp.Fetch? =
        defaultRemote(status?.upstream, remotes)?.let { GitNetworkOp.Fetch(it.name) }

    /**
     * A branch with an upstream pushes there with plain `git push`; one without
     * is published to the default remote and gains that upstream. Null when
     * there is nowhere to push (no remote, or a detached HEAD).
     */
    fun pushOp(status: GitStatus, remotes: List<GitRemoteInfo>, forceWithLease: Boolean = false): GitNetworkOp.Push? {
        if (status.detached) return null
        if (status.upstream != null) return GitNetworkOp.Push(null, null, forceWithLease = forceWithLease)
        val remote = defaultRemote(null, remotes) ?: return null
        return GitNetworkOp.Push(remote.name, status.branch, setUpstream = true, forceWithLease = forceWithLease)
    }

    fun kindOf(op: GitNetworkOp): GitOperationKind = when (op) {
        is GitNetworkOp.Fetch -> GitOperationKind.FETCH
        is GitNetworkOp.Pull -> GitOperationKind.PULL
        is GitNetworkOp.Push -> when {
            op.forceWithLease -> GitOperationKind.FORCE_PUSH
            op.setUpstream -> GitOperationKind.PUBLISH
            else -> GitOperationKind.PUSH
        }
    }
}
