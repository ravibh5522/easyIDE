package dev.easyide.sandbox.external

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.easyide.sandbox.SandboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mirrors a project's files to a folder the user picked outside app-private
 * storage (SD card, "Internal storage" as the OS shows it, a cloud-backed
 * `DocumentsProvider`) via the Storage Access Framework.
 *
 * This exists because of one hard constraint: [dev.easyide.sandbox.backend.ProotLauncher]
 * enters a project by bind-mounting its directory into the guest rootfs, and a
 * bind mount needs a real filesystem path. A SAF tree is reachable only as a
 * `content://` URI - there is no path to mount. So the project's *working*
 * copy always stays in app-private storage (what the sandbox actually runs
 * against); a linked external folder is a **mirror** kept in step with it, not
 * a substitute for it.
 *
 * The mirror is deliberately **additive-only**: it creates and overwrites
 * files the project owns, but [deleteFile] is the only thing that ever removes
 * something from the linked folder, and only for the exact relative path the
 * project itself just deleted. Nothing here ever scans the destination and
 * wipes whatever does not match the source - that would risk deleting a file
 * the user placed in that folder through some other app.
 */
class ExternalFolderSync(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    data class ImportResult(val filesCopied: Int, val bytesCopied: Long)

    /** True when this app still holds a read+write grant for [treeUri]. */
    fun hasAccess(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    /**
     * Persists the grant handed back by the `ACTION_OPEN_DOCUMENT_TREE` picker
     * result so it survives past this activity result callback - and past app
     * restarts. Must be called with the exact [Uri] the picker returned, before
     * that transient grant expires.
     */
    fun takePersistableAccess(treeUri: Uri): Result<Unit> = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Releases the grant. Called when a project is unlinked or deleted. */
    fun releaseAccess(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Display name for the picked folder, for showing the user what is linked. */
    fun displayName(treeUri: Uri): String? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()

    /**
     * One-time recursive copy from [treeUri] into [destDir], used when a
     * project is created against a folder that already has files in it.
     * Streamed, not buffered in memory - a picked folder's size is unbounded.
     */
    suspend fun importAll(treeUri: Uri, destDir: File): Result<ImportResult> = runCatching {
        withContext(ioDispatcher) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw SandboxError.StorageFailure("cannot open $treeUri")
            var files = 0
            var bytes = 0L
            fun walk(doc: DocumentFile, into: File) {
                doc.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    when {
                        child.isDirectory -> {
                            val childDir = File(into, name).apply { mkdirs() }
                            walk(child, childDir)
                        }

                        child.isFile -> {
                            val target = File(into, name)
                            context.contentResolver.openInputStream(child.uri)?.use { input ->
                                target.outputStream().use { output -> bytes += input.copyTo(output) }
                            }
                            files++
                        }
                    }
                }
            }
            walk(root, destDir)
            ImportResult(files, bytes)
        }
    }

    /** Creates or overwrites one file in the linked folder. */
    suspend fun writeFile(treeUri: Uri, relativePath: String, content: ByteArray): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val (parentSegments, name) = splitPath(relativePath)
            val root = openRoot(treeUri)
            val parent = resolveOrCreateDir(root, parentSegments)
                ?: throw SandboxError.StorageFailure("cannot resolve folder for $relativePath")
            val leaf = parent.findFile(name)?.takeIf { it.isFile }
                ?: parent.createFile(OCTET_STREAM, name)
                ?: throw SandboxError.StorageFailure("cannot create $relativePath")
            context.contentResolver.openOutputStream(leaf.uri, "wt")?.use { it.write(content) }
                ?: throw SandboxError.StorageFailure("cannot open $relativePath for writing")
        }
    }

    /** Creates an (empty) directory in the linked folder, including its parents. */
    suspend fun createDirectory(treeUri: Uri, relativePath: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val segments = relativePath.split('/').filter { it.isNotEmpty() }
            resolveOrCreateDir(openRoot(treeUri), segments)
                ?: throw SandboxError.StorageFailure("cannot create folder $relativePath")
        }
    }

    /**
     * Removes one file or directory from the linked folder - a directory's
     * contents go with it, the same as `rm -rf`, because that is what
     * [androidx.documentfile.provider.DocumentFile.delete] does for a
     * directory document. Safe here specifically because the path is always
     * one the project itself just deleted or renamed away from, never an
     * arbitrary scan of the destination.
     *
     * A missing path is not an error - the two sides are already in the state
     * the caller wants.
     */
    suspend fun delete(treeUri: Uri, relativePath: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val (parentSegments, name) = splitPath(relativePath)
            val parent = findDir(openRoot(treeUri), parentSegments) ?: return@withContext
            val leaf = parent.findFile(name) ?: return@withContext
            leaf.delete()
        }
    }

    private fun openRoot(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw SandboxError.StorageFailure("cannot open $treeUri")

    private fun splitPath(relativePath: String): Pair<List<String>, String> {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "blank relative path" }
        return segments.dropLast(1) to segments.last()
    }

    /** Walks existing directories only; never creates. Used before a delete. */
    private fun findDir(root: DocumentFile, segments: List<String>): DocumentFile? =
        segments.fold(root as DocumentFile?) { dir, segment ->
            dir?.findFile(segment)?.takeIf { it.isDirectory }
        }

    /** Walks [segments], creating any directory that does not exist yet. */
    private fun resolveOrCreateDir(root: DocumentFile, segments: List<String>): DocumentFile? =
        segments.fold(root as DocumentFile?) { dir, segment ->
            dir ?: return null
            val existing = dir.findFile(segment)
            when {
                existing != null && existing.isDirectory -> existing
                existing != null -> null // a file sits where a directory is expected - a real conflict
                else -> dir.createDirectory(segment)
            }
        }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}
