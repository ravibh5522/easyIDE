package dev.easyide.sandbox.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk

enum class GitRefKind { LOCAL, REMOTE, TAG }

/** A branch or tag label on a commit; [name] is short (`main`, `origin/main`, `v1.0`). */
data class GitRef(val name: String, val kind: GitRefKind, val isCurrent: Boolean)

/**
 * Every branch and tag keyed by the FULL id of the commit it points at, for
 * labelling the history graph. Annotated tags are peeled to their commit, and
 * `origin/HEAD` is skipped because it is a pointer, not a label. Within a
 * commit the current branch comes first, then other local branches, remote
 * branches and tags, each alphabetical.
 */
fun GitRepository.refsByCommit(): Map<String, List<GitRef>> {
    val current = repository.fullBranch
    val labels = mutableListOf<Pair<String, GitRef>>()
    RevWalk(repository).use { walk ->
        for (ref in repository.refDatabase.getRefsByPrefix(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS)) {
            val kind = kindOf(ref) ?: continue
            // Also matched by name, as branchInfos does, in case a checkout wrote it as a plain ref.
            if (ref.isSymbolic || ref.name.endsWith("/HEAD")) continue
            val commit = walk.peel(ref) ?: continue
            val short = Repository.shortenRefName(ref.name)
            labels += commit to GitRef(short, kind, isCurrent = ref.name == current)
        }
    }
    val order = compareBy<GitRef>({ !it.isCurrent }, { it.kind.ordinal }, { it.name })
    return labels.groupBy({ it.first }, { it.second }).mapValues { (_, refs) -> refs.sortedWith(order) }
}

private fun kindOf(ref: Ref): GitRefKind? = when {
    ref.name.startsWith(Constants.R_HEADS) -> GitRefKind.LOCAL
    ref.name.startsWith(Constants.R_REMOTES) -> GitRefKind.REMOTE
    ref.name.startsWith(Constants.R_TAGS) -> GitRefKind.TAG
    else -> null
}

/** The commit id a ref reaches, through any annotated tag; null when it points at a non-commit (a tagged tree or blob). */
private fun RevWalk.peel(ref: Ref): String? = (peel(parseAny(ref.objectId)) as? RevCommit)?.name
