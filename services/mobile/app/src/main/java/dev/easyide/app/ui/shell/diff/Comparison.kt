package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.sandbox.git.DiffEnd

/**
 * What a `git-diff` document compares: one repository-relative [path] between two ends
 * (shell-model.md section 4). It is the whole identity of the document, so it round-trips through
 * the URI `git-diff:///<path>?base=<end>&head=<end>` and a restored tab needs nothing else.
 *
 * A working tree is only ever the head, and an end is never compared with itself: [of] refuses
 * both, so a comparison that exists is one a provider can be asked about.
 */
data class Comparison(val path: String, val base: DiffEnd, val head: DiffEnd) {

    /** Null for a path a URI cannot carry (a file name with a control character). */
    val uri: DocumentUri? get() = DocumentUri.gitDiff("/$path", DiffEnds.token(base), DiffEnds.token(head))

    /** Staged changes are HEAD against the index; unstaged ones are the index against the working tree. */
    val isStaged: Boolean get() = base == DiffEnd.Rev("HEAD") && head == DiffEnd.Index
    val isUnstaged: Boolean get() = base == DiffEnd.Index && head == DiffEnd.Worktree

    /** What the comparison's document is called: the file, and what each side is. */
    val subject: DiffSubject get() = DiffSubject(path, label(base), label(head))

    private fun label(end: DiffEnd): SideLabel = when (end) {
        DiffEnd.Index -> SideLabel.Index
        DiffEnd.Worktree -> SideLabel.Worktree
        DiffEnd.Empty -> SideLabel.Nothing
        is DiffEnd.Rev -> SideLabel.Named(GitDocuments.shortId(end.name))
    }

    companion object {
        fun staged(path: String) = Comparison(path, DiffEnd.Rev("HEAD"), DiffEnd.Index)
        fun unstaged(path: String) = Comparison(path, DiffEnd.Index, DiffEnd.Worktree)

        /** The comparison [uri] names, or null when it is not a well-formed `git-diff` URI. */
        fun of(uri: DocumentUri): Comparison? {
            if (uri.scheme != "git-diff" || uri.segments.isEmpty()) return null
            val base = uri.query["base"]?.let(DiffEnds::parse) ?: return null
            val head = uri.query["head"]?.let(DiffEnds::parse) ?: return null
            if (base == head || base == DiffEnd.Worktree || head == DiffEnd.Empty) return null
            return Comparison(uri.segments.joinToString("/"), base, head)
        }
    }
}

/**
 * The text of a [DiffEnd] inside a URI. `index`, `worktree` and `empty` are reserved words, so a
 * branch with one of those names cannot be compared by name (its commit id still can).
 */
object DiffEnds {
    private const val INDEX = "index"
    private const val WORKTREE = "worktree"
    private const val EMPTY = "empty"

    fun token(end: DiffEnd): String = when (end) {
        DiffEnd.Index -> INDEX
        DiffEnd.Worktree -> WORKTREE
        DiffEnd.Empty -> EMPTY
        is DiffEnd.Rev -> end.name
    }

    fun parse(token: String): DiffEnd? = when {
        token.isBlank() -> null
        token == INDEX -> DiffEnd.Index
        token == WORKTREE -> DiffEnd.Worktree
        token == EMPTY -> DiffEnd.Empty
        else -> DiffEnd.Rev(token)
    }
}
