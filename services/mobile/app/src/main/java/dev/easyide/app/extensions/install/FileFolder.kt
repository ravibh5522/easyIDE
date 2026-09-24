package dev.easyide.app.extensions.install

import dev.easyide.extensions.authoring.IgnoreRules
import dev.easyide.extensions.authoring.PackageIgnores
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * An extension source folder on the host filesystem (a project folder, a folder "Create
 * extension" just wrote) as a [FolderNode], with the author-side files the CLI packer drops
 * left out: `test/`, `dist/`, `.gitignore`, `.easyide-ext.json`, keys and whatever the folder's
 * `.easyextignore` names ([PackageIgnores]). So installing a folder here installs what
 * `easyide-ext package` would ship. Symbolic links are refused, as in a package.
 */
class FileFolder private constructor(
    private val file: File,
    private val rel: String,
    private val rules: IgnoreRules,
) : FolderNode {
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory

    override fun children(): List<FolderNode> {
        val entries = file.listFiles() ?: throw IOException("cannot list ${file.path}")
        return entries.sortedBy { it.name }.mapNotNull { child ->
            val path = if (rel.isEmpty()) child.name else "$rel/${child.name}"
            if (Files.isSymbolicLink(child.toPath())) {
                if (rules.ignored(path) || rules.ignored("$path/")) return@mapNotNull null
                throw PackageRefused("symbolic link: $path")
            }
            if (rules.ignored(if (child.isDirectory) "$path/" else path)) null else FileFolder(child, path, rules)
        }
    }

    override fun open(): InputStream = file.inputStream()

    companion object {
        /** [root] with the default excludes plus its own `.easyextignore`. */
        fun of(root: File): FileFolder {
            val ignore = File(root, PackageIgnores.IGNORE_FILE).takeIf { it.isFile }?.readText()
            return FileFolder(root, "", PackageIgnores.rules(ignore))
        }
    }
}
