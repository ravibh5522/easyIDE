package dev.easyide.sandbox

import android.net.Uri
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.sandbox.files.SafeTree
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.store.SandboxStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates, renames, duplicates, deletes and re-homes projects. A project's directory lives outside every
 * rootfs, so attaching it to a different environment is a metadata change plus
 * a different bind mount - no copying. See
 * docs/decision/0005-sandbox-environment-sharing-model.md.
 */
class ProjectManager(
    private val store: SandboxStore,
    private val paths: SandboxPaths,
    private val projectFiles: ProjectFiles,
    private val externalFolderSync: ExternalFolderSync,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    val projects: Flow<List<ProjectRecord>> = store.state.map { it.projects }

    /**
     * @param environmentId an existing environment - reusing one is how several
     *   projects share a toolchain instead of each paying a full provision.
     * @param externalFolderUri a SAF tree URI (already granted persistable
     *   access by the caller - see [ExternalFolderSync.takePersistableAccess])
     *   the project's files should be mirrored to. If that folder already has
     *   files in it, they are imported as the project's starting content
     *   instead of the usual starter files.
     * @param seedStarterFiles false leaves the directory empty, for callers that
     *   fill it themselves (a `git clone` refuses a non-empty target).
     */
    suspend fun create(
        name: String,
        environmentId: String,
        externalFolderUri: String? = null,
        seedStarterFiles: Boolean = true,
    ): Result<ProjectRecord> = runCatching {
        val state = store.current()
        val trimmed = validName(state.projects, name)
        state.environment(environmentId) ?: throw SandboxError.EnvironmentNotFound(environmentId)

        val now = clock()
        val project = ProjectRecord(
            id = idGenerator(),
            name = trimmed,
            environmentId = environmentId,
            createdAtEpochMs = now,
            lastOpenedAtEpochMs = now,
            externalFolderUri = externalFolderUri,
        )

        withContext(ioDispatcher) {
            paths.ensureBaseDirs()
            val dir = paths.projectDir(project.id)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw SandboxError.StorageFailure("create ${dir.absolutePath}")
            }
        }

        try {
            val importedFromExternal = externalFolderUri?.let { uri ->
                val result = externalFolderSync.importAll(Uri.parse(uri), paths.projectDir(project.id))
                result.getOrThrow().filesCopied > 0
            } ?: false

            // Starter files so the editor opens onto something real rather than
            // an empty tree - unless the linked folder already had content,
            // which takes priority over inventing placeholder files on top of it.
            if (!importedFromExternal && seedStarterFiles) {
                projectFiles.seedStarterFiles(project.id, trimmed)
                externalFolderUri?.let { mirrorProjectOut(project.id, Uri.parse(it)) }
            }
        } catch (cause: Exception) {
            // The import the user asked for did not happen - leaving a
            // half-populated directory nobody references would be silent
            // garbage, not a recoverable project.
            withContext(ioDispatcher) { SafeTree.deleteRecursively(paths.projectDir(project.id)) }
            throw cause
        }

        store.update { it.upsertProject(project) }
        project
    }

    // -------------------------------------------------------- mirroring
    //
    // Every method below is a no-op (Result.success(Unit)) when the project
    // has no linked folder, so call sites in the workspace do not need to
    // check first. Failures are never thrown past this boundary - a sync
    // problem (permission revoked, SD card removed, provider rejected the
    // write) must never be mistaken for the app-private save itself failing,
    // since that save already succeeded by the time any of these run.

    /** Mirrors one file's current content into the linked folder, if any. */
    suspend fun mirrorWrite(projectId: String, relativePath: String, content: ByteArray): Result<Unit> =
        withExternalFolder(projectId) { uri -> externalFolderSync.writeFile(uri, relativePath, content) }

    /** Mirrors an empty directory into the linked folder, if any. */
    suspend fun mirrorCreateDirectory(projectId: String, relativePath: String): Result<Unit> =
        withExternalFolder(projectId) { uri -> externalFolderSync.createDirectory(uri, relativePath) }

    /** Removes a file or directory (recursively) from the linked folder, if any. */
    suspend fun mirrorDelete(projectId: String, relativePath: String): Result<Unit> =
        withExternalFolder(projectId) { uri -> externalFolderSync.delete(uri, relativePath) }

    /**
     * Mirrors whatever is currently on disk at [relativePath] - a single file,
     * or a directory and everything under it - into the linked folder, if any.
     * The counterpart to [mirrorWrite] for operations (rename, move, paste)
     * where "what changed" is identified by path rather than content already
     * held in memory; type is read from disk rather than passed in, since a
     * rename or move can apply to either.
     */
    suspend fun mirrorPath(projectId: String, relativePath: String): Result<Unit> =
        withExternalFolder(projectId) { uri ->
            val file = File(paths.projectDir(projectId), relativePath)
            when {
                file.isDirectory -> externalFolderSync.createDirectory(uri, relativePath).onSuccess {
                    projectFiles.list(projectId, relativePath).getOrNull()?.let { children ->
                        mirrorTreeOut(projectId, uri, children)
                    }
                }

                file.isFile -> externalFolderSync.writeFile(uri, relativePath, file.readBytes())
                else -> Result.success(Unit) // already gone - nothing to mirror
            }
        }

    private suspend fun withExternalFolder(projectId: String, action: suspend (Uri) -> Result<Unit>): Result<Unit> {
        val uri = store.current().project(projectId)?.externalFolderUri?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        } ?: return Result.success(Unit)
        return action(uri)
    }

    /** Mirrors the project's current app-private content into [treeUri]. Best-effort. */
    private suspend fun mirrorProjectOut(projectId: String, treeUri: Uri) {
        projectFiles.list(projectId).getOrNull()?.let { root -> mirrorTreeOut(projectId, treeUri, root) }
    }

    private suspend fun mirrorTreeOut(projectId: String, treeUri: Uri, nodes: List<FileNode>) {
        nodes.forEach { node ->
            if (node.isDirectory) {
                externalFolderSync.createDirectory(treeUri, node.relativePath)
                projectFiles.list(projectId, node.relativePath).getOrNull()?.let { children ->
                    mirrorTreeOut(projectId, treeUri, children)
                }
            } else {
                val file = File(paths.projectDir(projectId), node.relativePath)
                if (file.isFile) externalFolderSync.writeFile(treeUri, node.relativePath, file.readBytes())
            }
        }
    }

    /** Moves a project to a different environment. Cheap: metadata only. */
    suspend fun reassignEnvironment(projectId: String, environmentId: String): Result<ProjectRecord> =
        runCatching {
            val state = store.current()
            val project = state.project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
            state.environment(environmentId) ?: throw SandboxError.EnvironmentNotFound(environmentId)

            val updated = project.copy(environmentId = environmentId)
            store.update { it.upsertProject(updated) }
            updated
        }

    /**
     * Renames a project. Metadata only: the directory is named by id, so nothing
     * on disk moves and open workspaces keep working.
     */
    suspend fun rename(projectId: String, newName: String): Result<ProjectRecord> = runCatching {
        val state = store.current()
        val project = state.project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
        val trimmed = validName(state.projects.filter { it.id != projectId }, newName)

        val updated = project.copy(name = trimmed)
        store.update { it.upsertProject(updated) }
        updated
    }

    /**
     * Copies a project - files, git history and all - under a new id, attached
     * to the same environment. Environments are shared by reference, so nothing
     * of the rootfs is copied.
     *
     * The copy is deliberately not linked to the original's external folder:
     * two projects mirroring into one folder would overwrite each other.
     */
    suspend fun duplicate(projectId: String, newName: String): Result<ProjectRecord> = runCatching {
        val state = store.current()
        val source = state.project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
        val trimmed = validName(state.projects, newName)

        val now = clock()
        val copy = ProjectRecord(
            id = idGenerator(),
            name = trimmed,
            environmentId = source.environmentId,
            createdAtEpochMs = now,
            lastOpenedAtEpochMs = now,
        )
        withContext(ioDispatcher) {
            val target = paths.projectDir(copy.id)
            try {
                paths.ensureBaseDirs()
                SafeTree.copyRecursively(paths.projectDir(source.id), target)
            } catch (cause: Exception) {
                // A half-copied directory nobody references is silent garbage.
                SafeTree.deleteRecursively(target)
                throw SandboxError.StorageFailure("copy project '${source.name}'", cause)
            }
        }
        store.update { it.upsertProject(copy) }
        copy
    }

    /**
     * Removes the project record and, when [deleteFiles], its app-private
     * working copy. Never touches an environment - projects only reference
     * them - and never follows a symlink out of the project directory. A linked
     * external folder is released, not emptied: it belongs to the user.
     */
    suspend fun delete(projectId: String, deleteFiles: Boolean): Result<Unit> = runCatching {
        val project = store.current().project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
        if (deleteFiles) {
            withContext(ioDispatcher) { SafeTree.deleteRecursively(paths.projectDir(projectId)) }
        }
        // Release the grant rather than leaving it held forever - it is
        // otherwise invisible to the user and to Android's permission list.
        project.externalFolderUri?.let { externalFolderSync.releaseAccess(Uri.parse(it)) }
        store.update { it.removeProject(projectId) }
    }

    /** The trimmed name, or the [SandboxError]/[IllegalArgumentException] for why it is unusable. */
    private fun validName(others: List<ProjectRecord>, name: String): String =
        when (ProjectNames.problem(name, others.map { it.name })) {
            ProjectNames.Problem.BLANK -> throw IllegalArgumentException("Project name must not be blank")
            ProjectNames.Problem.DUPLICATE -> throw SandboxError.DuplicateName(name.trim())
            null -> name.trim()
        }

    suspend fun markOpened(projectId: String) {
        store.update { state ->
            val project = state.project(projectId) ?: return@update state
            state.upsertProject(project.copy(lastOpenedAtEpochMs = clock()))
        }
    }

    /**
     * Fails unless the project's environment is provisioned - opening a project
     * into a half-built rootfs would surface as confusing shell errors instead
     * of an actionable message.
     */
    suspend fun requireReadyEnvironment(projectId: String): Result<Unit> = runCatching {
        val state = store.current()
        val project = state.project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
        val environment = state.environment(project.environmentId)
            ?: throw SandboxError.EnvironmentNotFound(project.environmentId)
        if (environment.state != EnvironmentState.READY) {
            throw SandboxError.EnvironmentNotReady(environment.id, environment.state.name)
        }
    }
}
