package dev.easyide.sandbox.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** [source]'s pair of ends: HEAD against the index, or the index against the working tree. */
fun GitRepository.fileDiff(path: String, source: DiffSource): FileDiff = when (source) {
    DiffSource.STAGED -> fileDiff(path, DiffEnd.Rev(Constants.HEAD), DiffEnd.Index)
    DiffSource.UNSTAGED -> fileDiff(path, DiffEnd.Index, DiffEnd.Worktree)
}

/**
 * The unified diff of one path between two ends, parsed into hunks.
 *
 * The size guard is JGit's own binary threshold: past
 * [GitDiffLimits.MAX_TEXT_BYTES] it reports "Binary files differ" without
 * reading the content, so a huge file is never pulled into memory. That marker
 * is then told apart from a real binary by measuring the sides.
 */
fun GitRepository.fileDiff(path: String, base: DiffEnd, head: DiffEnd): FileDiff {
    val out = ByteArrayOutputStream()
    var largest = 0L
    DiffFormatter(out).use { formatter ->
        formatter.setRepository(repository)
        formatter.setContext(CONTEXT_LINES)
        formatter.isDetectRenames = false
        formatter.pathFilter = PathFilter.create(path)
        formatter.setBinaryFileThreshold(GitDiffLimits.MAX_TEXT_BYTES.toInt())
        repository.newObjectReader().use { reader ->
            val entries = formatter.scan(iteratorFor(base, reader), iteratorFor(head, reader))
            entries.forEach { largest = maxOf(largest, sizeOf(it, head, path, reader)) }
            formatter.format(entries)
        }
    }
    val parsed = UnifiedDiffParser.parse(path, out.toString(Charsets.UTF_8))
    return if (parsed is FileDiff.Binary && largest > GitDiffLimits.MAX_TEXT_BYTES) {
        FileDiff.TooLarge(path, largest)
    } else {
        parsed
    }
}

/** An unborn HEAD is the empty tree (a first commit compares against nothing); any other unknown name is the caller's mistake. */
internal fun GitRepository.iteratorFor(end: DiffEnd, reader: ObjectReader): AbstractTreeIterator = when (end) {
    DiffEnd.Index -> DirCacheIterator(repository.readDirCache())
    DiffEnd.Worktree -> FileTreeIterator(repository)
    DiffEnd.Empty -> EmptyTreeIterator()
    is DiffEnd.Rev -> {
        val tree = repository.resolve("${end.name}^{tree}")
        when {
            tree != null -> CanonicalTreeParser(null, reader, tree)
            end.name == Constants.HEAD -> EmptyTreeIterator()
            else -> throw IllegalArgumentException("Unknown revision: ${end.name}")
        }
    }
}

private fun GitRepository.sizeOf(entry: DiffEntry, head: DiffEnd, path: String, reader: ObjectReader): Long {
    fun blob(id: AbbreviatedObjectId): Long {
        val full = id.toObjectId()?.takeIf { it != ObjectId.zeroId() } ?: return 0L
        return reader.getObjectSize(full, Constants.OBJ_BLOB)
    }
    val newSide = if (head == DiffEnd.Worktree) File(workTree, path).length() else blob(entry.newId)
    return maxOf(blob(entry.oldId), newSide)
}

/** Moves one hunk of the working-tree diff into the index. */
fun GitRepository.stageHunk(path: String, hunk: DiffHunk) {
    writeIndex(path, HunkPatch.apply(readIndexText(path), hunk))
}

/** Moves one hunk of the staged diff back out of the index; the working tree is untouched. */
fun GitRepository.unstageHunk(path: String, hunk: DiffHunk) {
    writeIndex(path, HunkPatch.revert(readIndexText(path), hunk))
}

/** Throws away one hunk of the working-tree diff. Destructive: callers confirm first. */
fun GitRepository.discardHunk(path: String, hunk: DiffHunk) {
    val file = File(workTree, path)
    val current = if (file.isFile) decodeText(file.readBytes()) else ""
    val reverted = HunkPatch.revert(current, hunk)
    val tracked = repository.readDirCache().findEntry(path) >= 0
    if (reverted.isEmpty() && !tracked) {
        file.delete()
    } else {
        file.parentFile?.mkdirs()
        file.writeBytes(reverted.toByteArray(Charsets.UTF_8))
    }
}

private fun GitRepository.readIndexText(path: String): String {
    val entry = repository.readDirCache().getEntry(path) ?: return ""
    val loader = repository.open(entry.objectId, Constants.OBJ_BLOB)
    check(loader.size <= GitDiffLimits.MAX_TEXT_BYTES) { "File too large for hunk actions; use the whole-file actions" }
    return decodeText(loader.bytes)
}

/**
 * Strict UTF-8: hunk text round-trips through a String, and a lossy decode
 * would silently rewrite the bytes of a Latin-1 file the user only meant to
 * stage part of.
 */
private fun decodeText(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    throw IllegalStateException("File is not UTF-8 text; use the whole-file actions", e)
}

/**
 * Replaces the index entry for [path] with [text], keeping its file mode.
 * An empty result removes the entry when nothing would remain to track: the
 * file is gone from the working tree (a staged deletion) or was never in HEAD
 * (un-staging the last hunk of a new file).
 */
private fun GitRepository.writeIndex(path: String, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val file = File(workTree, path)
    val cache = repository.lockDirCache()
    try {
        val existing = cache.getEntry(path)
        val editor = cache.editor()
        if (bytes.isEmpty() && (!file.exists() || !inHead(path))) {
            editor.add(DirCacheEditor.DeletePath(path))
        } else {
            val id = repository.newObjectInserter().use { inserter ->
                inserter.insert(Constants.OBJ_BLOB, bytes).also { inserter.flush() }
            }
            val mode = existing?.fileMode
                ?: if (file.canExecute()) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
            editor.add(object : DirCacheEditor.PathEdit(path) {
                override fun apply(entry: DirCacheEntry) {
                    entry.fileMode = mode
                    entry.setObjectId(id)
                    entry.length = bytes.size
                    // Epoch makes the entry "racily clean", so status re-hashes the file
                    // instead of trusting a timestamp that no longer describes the blob.
                    entry.setLastModified(Instant.EPOCH)
                }
            })
        }
        editor.commit()
    } finally {
        cache.unlock()
    }
}

private fun GitRepository.inHead(path: String): Boolean {
    val tree = repository.resolve("${Constants.HEAD}^{tree}") ?: return false
    return TreeWalk.forPath(repository, path, tree)?.use { true } ?: false
}

private const val CONTEXT_LINES = 3
