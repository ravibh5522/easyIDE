package dev.easyide.sandbox.git

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/**
 * One line of a hunk. [text] excludes the `\n` terminator but keeps a `\r`,
 * so a CRLF file survives a stage/revert round trip byte for byte.
 * [noNewlineAtEnd] is git's "\ No newline at end of file" marker for this line.
 */
data class DiffLine(val kind: DiffLineKind, val text: String, val noNewlineAtEnd: Boolean = false)

/**
 * A `@@ -oldStart,oldCount +newStart,newCount @@` block. Starts are 1-based;
 * a zero count means a pure insertion/deletion, whose start names the line
 * *before* the change (unified-diff convention).
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val section: String,
    val lines: List<DiffLine>,
) {
    val header: String get() = "@@ -${range(oldStart, oldCount)} +${range(newStart, newCount)} @@" +
        section.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

    /** The same change seen from the other side: what turns the new text back into the old. */
    fun inverted(): DiffHunk = DiffHunk(
        oldStart = newStart,
        oldCount = newCount,
        newStart = oldStart,
        newCount = oldCount,
        section = section,
        lines = lines.map {
            when (it.kind) {
                DiffLineKind.ADDED -> it.copy(kind = DiffLineKind.REMOVED)
                DiffLineKind.REMOVED -> it.copy(kind = DiffLineKind.ADDED)
                DiffLineKind.CONTEXT -> it
            }
        },
    )

    private fun range(start: Int, count: Int) = if (count == 1) "$start" else "$start,$count"
}

/** Which two states of a file a diff compares. */
enum class DiffSource {
    /** HEAD against the index: what the next commit will contain. */
    STAGED,

    /** The index against the working tree, which is also how an untracked file appears (all added). */
    UNSTAGED,
}

sealed interface FileDiff {
    val path: String

    /** [hunks] is empty for a change with no line content (a pure mode change, an empty new file). */
    data class Text(override val path: String, val hunks: List<DiffHunk>) : FileDiff {
        val added: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }
        val removed: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }
    }

    data class Binary(override val path: String) : FileDiff

    /** Over [GitDiffLimits.MAX_TEXT_BYTES] on one side: rendering it would freeze the UI, so it is not read. */
    data class TooLarge(override val path: String, val bytes: Long) : FileDiff
}

object GitDiffLimits {
    /** Beyond this a text diff is not computed; a phone-sized heap cannot lay out megabytes of lines. */
    const val MAX_TEXT_BYTES = 1_048_576L
}

/**
 * Parses the unified-diff text JGit's `DiffFormatter` writes for one file.
 *
 * Line-prefix driven, and bounded by each hunk's declared counts, so a content
 * line that happens to look like a header (`+++ b/x`, `@@ ...`) inside a hunk
 * is still content.
 */
object UnifiedDiffParser {

    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)$")

    fun parse(path: String, diff: String): FileDiff {
        val rows = diff.split('\n')
        if (rows.any { it.startsWith("Binary files ") || it.startsWith("GIT binary patch") }) {
            return FileDiff.Binary(path)
        }
        val hunks = mutableListOf<DiffHunk>()
        var i = rows.indexOfFirst { HUNK.matches(it) }
        while (i in rows.indices) {
            val m = HUNK.matchEntire(rows[i]) ?: break
            val (os, oc, ns, nc, section) = m.destructured
            val oldCount = oc.ifEmpty { "1" }.toInt()
            val newCount = nc.ifEmpty { "1" }.toInt()
            val lines = mutableListOf<DiffLine>()
            var oldLeft = oldCount
            var newLeft = newCount
            i++
            while (i < rows.size) {
                val row = rows[i]
                if (row.startsWith("\\")) {
                    if (lines.isNotEmpty()) lines[lines.lastIndex] = lines.last().copy(noNewlineAtEnd = true)
                    i++
                    continue
                }
                if (oldLeft == 0 && newLeft == 0) break
                val kind = when (row.firstOrNull()) {
                    '+' -> DiffLineKind.ADDED
                    '-' -> DiffLineKind.REMOVED
                    ' ' -> DiffLineKind.CONTEXT
                    else -> break
                }
                if (kind != DiffLineKind.ADDED) oldLeft--
                if (kind != DiffLineKind.REMOVED) newLeft--
                lines += DiffLine(kind, row.substring(1))
                i++
            }
            hunks += DiffHunk(os.toInt(), oldCount, ns.toInt(), newCount, section, lines)
            while (i < rows.size && !HUNK.matches(rows[i])) i++
        }
        return FileDiff.Text(path, hunks)
    }
}
