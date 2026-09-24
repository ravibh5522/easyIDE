package dev.easyide.sandbox.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.URIish

data class GitRemoteInfo(val name: String, val url: String)

private val REMOTE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

fun isValidRemoteName(name: String): Boolean = REMOTE_NAME.matches(name)

fun GitRepository.remoteInfos(): List<GitRemoteInfo> =
    git.remoteList().call().mapNotNull { config ->
        config.urIs.firstOrNull()?.let { GitRemoteInfo(config.name, it.toString()) }
    }.sortedBy { it.name }

/**
 * Adds a remote, configuring its default fetch refspec, without touching the
 * network - the first fetch is a separate, cancellable operation.
 */
fun GitRepository.addRemote(name: String, url: String) {
    require(isValidRemoteName(name)) { "Not a valid remote name: $name" }
    val uri = URIish(url.trim())
    git.remoteAdd().setName(name).setUri(uri).call()
}

fun GitRepository.removeRemote(name: String) {
    git.remoteRemove().setRemoteName(name).call()
}

/**
 * True when HEAD is reachable from any remote-tracking ref, i.e. someone else
 * may already have it. Amending such a commit means the next push must be
 * forced, so the UI warns first.
 */
fun GitRepository.isHeadPushed(): Boolean {
    val head = repository.resolve(Constants.HEAD) ?: return false
    return RevWalk(repository).use { walk ->
        val headCommit = walk.parseCommit(head)
        repository.refDatabase.getRefsByPrefix(Constants.R_REMOTES).any { ref ->
            val id = ref.objectId ?: return@any false
            walk.isMergedInto(headCommit, walk.parseCommit(id))
        }
    }
}
