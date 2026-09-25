package dev.easyide.sandbox.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/** A tag name git would accept, checked before touching the repository. */
fun isValidTagName(name: String): Boolean =
    name.isNotBlank() && Repository.isValidRefName(Constants.R_TAGS + name) && !name.startsWith("-")

/**
 * Tags the commit [rev] names. A blank [message] makes a lightweight tag;
 * otherwise an annotated one, which JGit only creates with a tagger, hence
 * the identity parameters. An existing tag is refused rather than moved,
 * because moving a tag silently rewrites what a release pointed at.
 */
fun GitRepository.createTag(
    name: String,
    rev: String,
    message: String?,
    taggerName: String,
    taggerEmail: String,
) {
    require(isValidTagName(name)) { "Not a valid tag name: $name" }
    require(repository.findRef(Constants.R_TAGS + name) == null) { "Tag already exists: $name" }
    val id = repository.resolve("$rev^{commit}") ?: throw IllegalArgumentException("Unknown revision: $rev")
    val target = RevWalk(repository).use { it.parseCommit(id) }
    // Signing is off explicitly: a `tag.gpgSign` in the user's config would demand a key we do not have.
    val command = git.tag().setName(name).setObjectId(target).setSigned(false)
    if (message.isNullOrBlank()) {
        command.setAnnotated(false)
    } else {
        command.setAnnotated(true).setMessage(message).setTagger(PersonIdent(taggerName, taggerEmail))
    }
    command.call()
}
