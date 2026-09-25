package dev.easyide.app.session

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A stored session: the snapshot plus a reader for its backup files.
 */
class StoredSession(val snapshot: SessionSnapshot, private val backups: File) {
    /** The unsaved text a tab's [BackupRef] points at, or null when the file is gone. */
    fun backupText(ref: BackupRef): String? {
        val file = File(backups, ref.file).takeIf { it.isFile && SessionStore.isBackupName(ref.file) } ?: return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * Per-project session files in app-private storage: `<root>/<projectId>/session.json` and
 * `<root>/<projectId>/backups/<name>`, the dirty buffers' text.
 *
 * Every write is temp file + fsync + atomic rename, so a kill or power loss at any instant
 * leaves either the previous complete file or the new one, never a torn one. Backups are
 * written before the snapshot that references them and unreferenced ones are pruned after,
 * so the snapshot never points at a missing backup. Callers serialise access per project.
 */
class SessionStore(private val root: File) {

    /** Null when there is no usable stored session; an unreadable one is treated as absent, since it can only cost a restore. */
    fun load(projectId: String): StoredSession? {
        val dir = projectDir(projectId)
        val file = File(dir, SNAPSHOT_FILE).takeIf { it.isFile } ?: return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        val snapshot = SessionSnapshotCodec.decode(text) ?: return null
        return StoredSession(snapshot, File(dir, BACKUP_DIR))
    }

    /**
     * Writes [changedBackups] (name -> text; unchanged buffers are not rewritten), then
     * [snapshot], then removes backups it no longer references. An empty snapshot clears the
     * project's session instead.
     */
    fun save(snapshot: SessionSnapshot, changedBackups: Map<String, String>) {
        if (snapshot.isEmpty) {
            clear(snapshot.projectId)
            return
        }
        val dir = projectDir(snapshot.projectId)
        val backups = File(dir, BACKUP_DIR)
        backups.mkdirs()
        changedBackups.forEach { (name, text) ->
            require(isBackupName(name)) { "not a backup file name: $name" }
            writeAtomically(File(backups, name), text.toByteArray(Charsets.UTF_8))
        }
        writeAtomically(File(dir, SNAPSHOT_FILE), SessionSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
        val referenced = snapshot.tabs.mapNotNull { it.backup?.file }.toSet()
        backups.listFiles().orEmpty()
            .filter { it.name !in referenced }
            .forEach(File::delete)
    }

    fun clear(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }

    /** Projects that have a stored session, for finding the ones whose project was deleted while the app was not running. */
    fun storedIds(): Set<String> = root.listFiles().orEmpty().filter { it.isDirectory }.mapTo(HashSet()) { it.name }

    private fun projectDir(projectId: String): File {
        require(SAFE_ID.matches(projectId) && projectId != "." && projectId != "..") { "unsafe project id: $projectId" }
        return File(root, projectId)
    }

    companion object {
        const val SNAPSHOT_FILE = "session.json"
        const val BACKUP_DIR = "backups"

        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
        private val BACKUP_NAME = Regex("[0-9a-f]{16}\\.txt")

        /** A backup file is named from its tab's path (see [backupNameFor]); nothing else is read or written. */
        fun isBackupName(name: String): Boolean = BACKUP_NAME.matches(name)

        /** Stable per path, so a tab's successive backups overwrite one file. */
        fun backupNameFor(relativePath: String): String = Hashes.sha256Hex(relativePath).take(16) + ".txt"

        internal fun writeAtomically(target: File, bytes: ByteArray) {
            val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private const val TEMP_SUFFIX = ".tmp"
    }
}
