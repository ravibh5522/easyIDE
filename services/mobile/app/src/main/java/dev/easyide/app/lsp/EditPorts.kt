package dev.easyide.app.lsp

import dev.easyide.lsp.manager.EditPortFactory
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.text.TextEdits
import dev.easyide.lsp.workspace.EditPort
import dev.easyide.lsp.workspace.EditResult
import dev.easyide.lsp.workspace.HostLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a project's edits go. While its workspace is open, the workspace's port edits open
 * buffers (and falls back to disk for the rest); otherwise every edit goes to disk. Routing is
 * decided per call, so a `workspace/applyEdit` that arrives after the workspace closed still
 * lands on disk instead of in a buffer nobody will save.
 */
class EditPortRegistry(private val files: EditPort) : EditPortFactory {

    private val workspaces = ConcurrentHashMap<Pair<String, String>, EditPort>()

    /** The workspace of (environment, project) edits buffers until the handle is closed. */
    fun register(environmentId: String, projectId: String, port: EditPort): AutoCloseable {
        val key = environmentId to projectId
        workspaces[key] = port
        return AutoCloseable { workspaces.remove(key, port) }
    }

    override fun forProject(environmentId: String, projectId: String): EditPort = object : EditPort {
        private fun port() = workspaces[environmentId to projectId] ?: files

        override suspend fun applyTextEdits(target: HostLocation, edits: List<TextEdit>, label: String) =
            port().applyTextEdits(target, edits, label)

        override suspend fun createFile(target: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean) =
            port().createFile(target, overwrite, ignoreIfExists)

        override suspend fun renameFile(from: HostLocation, to: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean) =
            port().renameFile(from, to, overwrite, ignoreIfExists)

        override suspend fun deleteFile(target: HostLocation, recursive: Boolean, ignoreIfNotExists: Boolean) =
            port().deleteFile(target, recursive, ignoreIfNotExists)
    }
}

/**
 * Edits on disk for files no editor holds. Targets arrive mapped and checked writable by
 * `WorkspaceEditApplier`; the options follow the LSP `CreateFile` / `RenameFile` /
 * `DeleteFile` semantics (`overwrite` beats `ignoreIfExists`).
 */
class DiskEditPort(private val io: CoroutineDispatcher) : EditPort {

    override suspend fun applyTextEdits(target: HostLocation, edits: List<TextEdit>, label: String): EditResult = disk {
        val file = target.file
        val text = file.readText()
        val next = TextEdits.apply(text, edits) ?: return@disk EditResult.Failed("overlapping edits for ${file.name}")
        if (next != text) file.writeText(next)
        EditResult.Ok
    }

    override suspend fun createFile(target: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult = disk {
        val file = target.file
        when {
            file.exists() && !overwrite && ignoreIfExists -> EditResult.Ok
            file.exists() && !overwrite -> EditResult.Failed("${file.name} already exists")
            else -> {
                file.parentFile?.mkdirs()
                file.writeText("")
                EditResult.Ok
            }
        }
    }

    override suspend fun renameFile(from: HostLocation, to: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult = disk {
        val src = from.file
        val dst = to.file
        when {
            !src.exists() -> EditResult.Failed("${src.name} does not exist")
            dst.exists() && !overwrite && ignoreIfExists -> EditResult.Ok
            dst.exists() && !overwrite -> EditResult.Failed("${dst.name} already exists")
            else -> {
                dst.parentFile?.mkdirs()
                if (dst.exists() && !dst.deleteRecursively()) return@disk EditResult.Failed("cannot replace ${dst.name}")
                if (src.renameTo(dst)) EditResult.Ok else EditResult.Failed("cannot rename ${src.name}")
            }
        }
    }

    override suspend fun deleteFile(target: HostLocation, recursive: Boolean, ignoreIfNotExists: Boolean): EditResult = disk {
        val file = target.file
        when {
            !file.exists() -> if (ignoreIfNotExists) EditResult.Ok else EditResult.Failed("${file.name} does not exist")
            file.isDirectory && !recursive && (file.list()?.isNotEmpty() == true) -> EditResult.Failed("${file.name} is not empty")
            (if (recursive) file.deleteRecursively() else file.delete()) -> EditResult.Ok
            else -> EditResult.Failed("cannot delete ${file.name}")
        }
    }

    /** File I/O is the boundary: a failure becomes this edit's reason, never a crash. */
    private suspend fun disk(block: () -> EditResult): EditResult = withContext(io) {
        try {
            block()
        } catch (e: IOException) {
            EditResult.Failed(e.message ?: "I/O error")
        } catch (e: SecurityException) {
            EditResult.Failed(e.message ?: "permission denied")
        }
    }
}
