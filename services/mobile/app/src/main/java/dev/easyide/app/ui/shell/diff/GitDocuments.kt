package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.UriPattern

/**
 * The two git document types of shell-model.md section 4: a comparison of one file
 * (`git-diff:///<path>?base=..&head=..`, see [Comparison]) and a commit (`git-commit://<sha>`).
 * Both restore from their URI alone, so they are restorable; a tab whose repository has moved on
 * shows what loading it produced, never a crash.
 *
 * The type's own title is only the fallback for a tab whose renderer is missing; the tab normally
 * carries the renderer's title (the file with its right side, or the short commit id).
 */
object GitDocuments {
    const val DIFF_TYPE = "easyide.git-diff"
    const val COMMIT_TYPE = "easyide.git-commit"

    /** Characters of a commit id a title shows, as `git log --oneline` does. */
    const val SHORT_ID = 7

    val diffType = DocumentType(DIFF_TYPE, UriPattern("git-diff"), { it.name }, { IconRef("diff") })

    val commitType = DocumentType(COMMIT_TYPE, UriPattern("git-commit"), { shortId(it.authority) }, { IconRef("commit") })

    fun commitUri(sha: String): DocumentUri? = DocumentUri.gitCommit(sha)

    /** The revision of a commit document, or null for any other URI. */
    fun commitOf(uri: DocumentUri?): String? = uri?.takeIf { it.scheme == "git-commit" }?.authority

    /** A full commit id shortened; a branch or tag name is already short and stays as it is. */
    fun shortId(rev: String): String = if (rev.length == FULL_ID_LENGTH && rev.all { it in HEX }) rev.take(SHORT_ID) else rev

    private const val FULL_ID_LENGTH = 40
    private val HEX = "0123456789abcdef".toSet()
}
