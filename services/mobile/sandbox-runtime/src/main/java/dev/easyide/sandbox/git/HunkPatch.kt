package dev.easyide.sandbox.git

/** The hunk no longer matches the text it was computed from; the caller should re-read the diff. */
class HunkMismatchException : Exception("The file changed since this diff was computed")

/**
 * Applies a single hunk to file text - the primitive behind hunk-level stage,
 * unstage and discard. Staging applies a working-tree hunk to the index text;
 * unstaging and discarding apply the *inverted* hunk to the index / working
 * text, so all three are [apply] with a different text and direction.
 *
 * The hunk is applied at its recorded position and every old-side line is
 * verified, never fuzzed: a hunk taken from a stale diff must fail, not land
 * on the wrong lines.
 */
object HunkPatch {

    fun apply(text: String, hunk: DiffHunk): String {
        val endsWithNewline = text.isEmpty() || text.endsWith("\n")
        val lines = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

        // A zero old count means an insertion after line oldStart, so the
        // splice point is oldStart itself, not oldStart - 1.
        val at = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
        val old = hunk.lines.filter { it.kind != DiffLineKind.ADDED }
        if (at < 0 || at + old.size > lines.size || old.size != hunk.oldCount) throw HunkMismatchException()
        old.forEachIndexed { k, line -> if (lines[at + k] != line.text) throw HunkMismatchException() }

        val new = hunk.lines.filter { it.kind != DiffLineKind.REMOVED }
        val result = lines.subList(0, at) + new.map { it.text } + lines.subList(at + old.size, lines.size)

        // The final newline only changes when the hunk touches the last line.
        val touchesEnd = at + old.size == lines.size
        val eol = if (touchesEnd) new.lastOrNull()?.noNewlineAtEnd != true else endsWithNewline
        return result.joinToString("\n") + if (result.isNotEmpty() && eol) "\n" else ""
    }

    /** Undoes [hunk] on [text], which must be the hunk's new side. */
    fun revert(text: String, hunk: DiffHunk): String = apply(text, hunk.inverted())
}
