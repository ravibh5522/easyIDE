package dev.easyide.sandbox.git

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository

/**
 * A branch as the branch sheet lists it. [name] is the short name (`main`, or
 * `origin/main` for a remote-tracking one); [upstream] is only known for local
 * branches.
 */
data class GitBranch(
    val name: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
    val shortId: String,
    val upstream: String?,
)

/** A branch name git would accept, checked before touching the repository. */
fun isValidBranchName(name: String): Boolean =
    name.isNotBlank() && Repository.isValidRefName(Constants.R_HEADS + name) && !name.startsWith("-")

fun GitRepository.branchInfos(): List<GitBranch> {
    val current = repository.fullBranch
    val local = git.branchList().call().map { ref ->
        val short = ref.name.removePrefix(Constants.R_HEADS)
        GitBranch(
            name = short,
            isRemote = false,
            isCurrent = ref.name == current,
            shortId = ref.objectId.name.take(SHORT_ID),
            upstream = BranchConfig(repository.config, short).remoteTrackingBranch
                ?.removePrefix(Constants.R_REMOTES),
        )
    }
    val remote = git.branchList().setListMode(ListMode.REMOTE).call()
        // `origin/HEAD` is a pointer to another branch, not a branch of its own.
        .filterNot { it.name.endsWith("/HEAD") }
        .map { ref ->
            GitBranch(
                name = ref.name.removePrefix(Constants.R_REMOTES),
                isRemote = true,
                isCurrent = false,
                shortId = ref.objectId.name.take(SHORT_ID),
                upstream = null,
            )
        }
    return local.sortedBy { it.name } + remote.sortedBy { it.name }
}

/** [startPoint] null branches from HEAD; a remote branch name starts a tracking branch. */
fun GitRepository.createBranch(name: String, startPoint: String?, checkout: Boolean) {
    require(isValidBranchName(name)) { "Not a valid branch name: $name" }
    val command = git.branchCreate().setName(name)
    if (startPoint != null) {
        command.setStartPoint(startPoint).setUpstreamMode(SetupUpstreamMode.TRACK)
    }
    command.call()
    if (checkout) git.checkout().setName(name).call()
}

/**
 * Switches to [name]. A remote-tracking name (`origin/feature`) first creates
 * the local branch of the same short name tracking it, which is what `git
 * switch feature` does when only the remote has it.
 */
fun GitRepository.switchBranch(name: String) {
    val remote = git.branchList().setListMode(ListMode.REMOTE).call()
        .firstOrNull { it.name == Constants.R_REMOTES + name }
    if (remote == null) {
        git.checkout().setName(name).call()
        return
    }
    val local = name.substringAfter('/')
    val exists = repository.findRef(Constants.R_HEADS + local) != null
    if (exists) {
        git.checkout().setName(local).call()
    } else {
        git.checkout().setCreateBranch(true).setName(local)
            .setStartPoint(remote.name).setUpstreamMode(SetupUpstreamMode.TRACK).call()
    }
}

/**
 * Deletes a local branch. Without [force], JGit throws `NotMergedException`
 * for a branch whose commits are reachable from nowhere else, which the
 * service maps to [GitFailureKind.UNMERGED_BRANCH] so the UI can warn first.
 */
fun GitRepository.deleteBranch(name: String, force: Boolean) {
    git.branchDelete().setBranchNames(name).setForce(force).call()
}

fun GitRepository.renameBranch(oldName: String, newName: String) {
    require(isValidBranchName(newName)) { "Not a valid branch name: $newName" }
    git.branchRename().setOldName(oldName).setNewName(newName).call()
}

private const val SHORT_ID = 7
