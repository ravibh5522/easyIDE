package dev.tabcode.sandbox

import android.net.Uri
import dev.tabcode.sandbox.external.ExternalFolderSync
import dev.tabcode.sandbox.files.FileNode
import dev.tabcode.sandbox.files.ProjectFiles
import dev.tabcode.sandbox.model.EnvironmentState
import dev.tabcode.sandbox.model.ProjectRecord
import dev.tabcode.sandbox.store.SandboxStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates and re-homes projects. A project's directory lives outside every
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
     */
    suspend fun create(
        name: String,
        environmentId: String,
        externalFolderUri: String? = null,
    ): Result<ProjectRecord> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Project name must not be blank" }

        val state = store.current()
        state.environment(environmentId) ?: throw SandboxError.EnvironmentNotFound(environmentId)
        if (state.projects.any { it.name.equals(trimmed, ignoreCase = true) }) {
            throw SandboxError.DuplicateName(trimmed)
        }

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
            if (!importedFromExternal) {
                projectFiles.seedStarterFiles(project.id, trimmed)
                externalFolderUri?.let { mirrorProjectOut(project.id, Uri.parse(it)) }
            }
        } catch (cause: Exception) {
            // The import the user asked for did not happen - leaving a
            // half-populated directory nobody references would be silent
            // garbage, not a recoverable project.
            withContext(ioDispatcher) { paths.projectDir(project.id).deleteRecursively() }
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

    suspend fun delete(projectId: String, deleteFiles: Boolean): Result<Unit> = runCatching {
        val project = store.current().project(projectId) ?: throw SandboxError.ProjectNotFound(projectId)
        if (deleteFiles) {
            withContext(ioDispatcher) { paths.projectDir(projectId).deleteRecursively() }
        }
        // Release the grant rather than leaving it held forever - it is
        // otherwise invisible to the user and to Android's permission list.
        project.externalFolderUri?.let { externalFolderSync.releaseAccess(Uri.parse(it)) }
        store.update { it.removeProject(projectId) }
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
