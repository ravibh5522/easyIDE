package dev.easyide.app.data.settings

import android.os.FileObserver
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * An app-private settings file (environment layer, keybindings, profiles).
 * Written through a temp file and a rename so a crash never leaves a
 * truncated file behind.
 */
class PlainFileIo(private val file: File, private val io: CoroutineDispatcher) : SettingsFileIo {

    override val path: String get() = file.path

    override suspend fun read(): FileRead = withContext(io) {
        // Boundary: filesystem.
        try {
            when {
                !file.exists() -> FileRead.Missing
                file.length() > SettingsPolicy.MAX_FILE_BYTES -> FileRead.TooLarge(file.length())
                else -> FileRead.Text(file.readText())
            }
        } catch (e: IOException) {
            FileRead.Failed(e.message ?: file.path)
        }
    }

    override suspend fun write(text: String): Result<Unit> = withContext(io) {
        runCatching {
            val dir = file.parentFile ?: throw IOException("no parent for ${file.path}")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            val temp = File.createTempFile(file.name, TEMP_SUFFIX, dir)
            try {
                temp.writeText(text)
                if (!temp.renameTo(file)) throw IOException("cannot replace ${file.path}")
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun delete(): Result<Unit> = withContext(io) {
        runCatching { if (file.exists() && !file.delete()) throw IOException("cannot delete ${file.path}") }
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * `<project>/.easyide/settings.json`, through [ProjectFiles] so the path checks
 * that keep a crafted symlink from reaching outside the project apply here too:
 * the file arrives with `git clone` and sandbox processes can write it.
 */
class ProjectFileIo(
    private val files: ProjectFiles,
    private val projectId: String,
    private val relativePath: String,
) : SettingsFileIo {

    override val path: String get() = files.projectRoot(projectId).resolve(relativePath).path

    override suspend fun read(): FileRead {
        val size = files.fileSize(projectId, relativePath).getOrElse { return FileRead.Failed(it.message ?: path) }
            ?: return FileRead.Missing
        if (size > SettingsPolicy.MAX_FILE_BYTES) return FileRead.TooLarge(size)
        return files.readText(projectId, relativePath).fold({ FileRead.Text(it) }, { FileRead.Failed(it.message ?: path) })
    }

    override suspend fun write(text: String): Result<Unit> = files.writeTextAtomic(projectId, relativePath, text)

    companion object {
        const val SETTINGS = ".easyide/settings.json"
        const val DIR = ".easyide"
    }
}

/**
 * Watches `<root>/.easyide/` for settings changes from outside the app (a
 * shell, git, another editor). The directory may not exist yet, so the root is
 * watched for its creation and the inner observer re-attached when it appears.
 */
class SettingsDirWatcher(private val root: File, private val onChange: () -> Unit) : AutoCloseable {

    private val dir = File(root, ProjectFileIo.DIR)
    private var inner: FileObserver? = null

    @Suppress("DEPRECATION") // the File constructor is API 29+; minSdk is 26
    private val outer = object : FileObserver(root.path, ROOT_EVENTS) {
        override fun onEvent(event: Int, path: String?) {
            if (path != ProjectFileIo.DIR) return
            attachInner()
            onChange()
        }
    }

    init {
        outer.startWatching()
        attachInner()
    }

    @Synchronized
    private fun attachInner() {
        inner?.stopWatching()
        @Suppress("DEPRECATION")
        inner = object : FileObserver(dir.path, DIR_EVENTS) {
            override fun onEvent(event: Int, path: String?) = onChange()
        }.also { it.startWatching() }
    }

    @Synchronized
    override fun close() {
        outer.stopWatching()
        inner?.stopWatching()
        inner = null
    }

    private companion object {
        const val ROOT_EVENTS = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM
        const val DIR_EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.CREATE
    }
}
