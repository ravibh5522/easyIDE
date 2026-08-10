package dev.tabcode.sandbox.files

import dev.tabcode.sandbox.SandboxError
import dev.tabcode.sandbox.SandboxPaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/** One entry in the explorer tree. */
data class FileNode(
    val name: String,
    /** Path relative to the project root - the id the UI passes back. */
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * Reads and writes files inside one project directory. Every public method
 * takes a project-relative path and refuses anything that escapes the project
 * root, so a crafted path cannot reach another project or app-private data.
 */
class ProjectFiles(
    private val paths: SandboxPaths,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun list(projectId: String, relativeDir: String = ""): Result<List<FileNode>> =
        runCatching {
            withContext(ioDispatcher) {
                val dir = resolve(projectId, relativeDir)
                if (!dir.isDirectory) return@withContext emptyList()
                val root = canonicalRoot(projectId)
                dir.listFiles()
                    .orEmpty()
                    .map { it.toNode(root) }
                    // Directories first, then case-insensitive by name: the
                    // ordering every file explorer uses.
                    .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
        }

    /**
     * Decides how - or whether - a file can be opened, applying [FilePolicy].
     *
     * Text vs binary is sniffed from the file's first bytes rather than its
     * extension: extensions lie, and opening an ELF object as text produced
     * unreadable output and a needlessly huge buffer.
     */
    suspend fun open(projectId: String, relativePath: String): Result<FileContent> = runCatching {
        withContext(ioDispatcher) {
            val file = resolve(projectId, relativePath)
            if (!file.isFile) throw SandboxError.StorageFailure("$relativePath is not a readable file")
            if (!file.canRead()) throw SandboxError.StorageFailure("$relativePath is not readable")

            val size = file.length()
            if (isBinary(file)) return@withContext binaryContent(file, size)

            when {
                size > FilePolicy.TEXT_VIEW_MAX_BYTES -> FileContent.Rejected(
                    "${FilePolicy.humanSize(size)} text file is too large to open " +
                        "(limit ${FilePolicy.humanSize(FilePolicy.TEXT_VIEW_MAX_BYTES)})"
                )

                // Large but readable: load a prefix read-only. Editing a
                // partial buffer would truncate the file on save.
                size > FilePolicy.TEXT_EDIT_MAX_BYTES -> FileContent.Text(
                    text = file.readPrefix(FilePolicy.TEXT_VIEW_PREFIX_BYTES.toInt()),
                    editable = false,
                    highlightingEnabled = false,
                    truncated = size > FilePolicy.TEXT_VIEW_PREFIX_BYTES,
                    totalBytes = size,
                )

                else -> FileContent.Text(
                    text = file.readText(),
                    editable = true,
                    highlightingEnabled = size <= FilePolicy.HIGHLIGHT_MAX_BYTES,
                    truncated = false,
                    totalBytes = size,
                )
            }
        }
    }

    private fun binaryContent(file: File, size: Long): FileContent =
        if (size > FilePolicy.BINARY_PREVIEW_MAX_BYTES) {
            FileContent.Rejected(
                "Binary file (${FilePolicy.humanSize(size)}) - too large to preview " +
                    "(limit ${FilePolicy.humanSize(FilePolicy.BINARY_PREVIEW_MAX_BYTES)})"
            )
        } else {
            FileContent.BinaryPreview(hexDump(file.readBytes()), size)
        }

    /** A NUL byte in the leading bytes is the standard text/binary heuristic. */
    private fun isBinary(file: File): Boolean {
        val head = file.readPrefixBytes(FilePolicy.SNIFF_BYTES)
        return head.any { it == NUL_BYTE }
    }

    private fun File.readPrefixBytes(limit: Int): ByteArray = inputStream().use { stream ->
        val buffer = ByteArray(limit)
        val read = stream.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    /** Decodes with replacement, since a prefix may split a multi-byte char. */
    private fun File.readPrefix(limit: Int): String =
        readPrefixBytes(limit).toString(Charsets.UTF_8)

    private fun hexDump(bytes: ByteArray): String = buildString {
        bytes.toList().chunked(HEX_BYTES_PER_ROW).forEachIndexed { rowIndex, row ->
            append("%08x  ".format(rowIndex * HEX_BYTES_PER_ROW))
            row.forEach { append("%02x ".format(it)) }
            repeat(HEX_BYTES_PER_ROW - row.size) { append("   ") }
            append(" |")
            row.forEach { byte ->
                val char = byte.toInt().toChar()
                append(if (char.isPrintable()) char else '.')
            }
            append("|\n")
        }
    }

    private fun Char.isPrintable(): Boolean = code in PRINTABLE_MIN..PRINTABLE_MAX

    suspend fun writeText(projectId: String, relativePath: String, content: String): Result<Unit> =
        runCatching {
            withContext(ioDispatcher) {
                val file = resolve(projectId, relativePath)
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }

    suspend fun createFile(projectId: String, relativePath: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val file = resolve(projectId, relativePath)
            if (file.exists()) throw SandboxError.DuplicateName(relativePath)
            file.parentFile?.mkdirs()
            if (!file.createNewFile()) throw SandboxError.StorageFailure("create $relativePath")
        }
    }

    suspend fun createDirectory(projectId: String, relativePath: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val dir = resolve(projectId, relativePath)
            if (dir.exists()) throw SandboxError.DuplicateName(relativePath)
            if (!dir.mkdirs()) throw SandboxError.StorageFailure("create ${dir.absolutePath}")
        }
    }

    suspend fun rename(projectId: String, relativePath: String, newName: String): Result<String> =
        runCatching {
            withContext(ioDispatcher) {
                require(newName.isNotBlank()) { "Name must not be blank" }
                // A name, not a path: renaming must not be a way to move a file
                // somewhere the path checks would otherwise reject.
                require(!newName.contains('/')) { "Name must not contain '/'" }

                val source = resolve(projectId, relativePath)
                if (!source.exists()) throw SandboxError.StorageFailure("rename $relativePath")
                val target = File(source.parentFile, newName)
                if (target.exists()) throw SandboxError.DuplicateName(newName)
                if (!source.renameTo(target)) throw SandboxError.StorageFailure("rename $relativePath")

                target.relativeTo(canonicalRoot(projectId)).path
            }
        }

    /** Copies a file or directory next to [targetDir], de-duplicating the name. */
    suspend fun copy(projectId: String, sourcePath: String, targetDir: String): Result<String> =
        runCatching {
            withContext(ioDispatcher) {
                val source = resolve(projectId, sourcePath)
                if (!source.exists()) throw SandboxError.StorageFailure("copy $sourcePath")
                val destinationDir = resolve(projectId, targetDir)
                if (!destinationDir.isDirectory) throw SandboxError.StorageFailure("copy into $targetDir")

                val target = uniqueName(destinationDir, source.name)
                if (source.isDirectory) source.copyRecursively(target) else source.copyTo(target)
                target.relativeTo(canonicalRoot(projectId)).path
            }
        }

    /** Copy then delete, so a move across the tree cannot lose data on failure. */
    suspend fun move(projectId: String, sourcePath: String, targetDir: String): Result<String> =
        runCatching {
            val copied = copy(projectId, sourcePath, targetDir).getOrThrow()
            delete(projectId, sourcePath).getOrThrow()
            copied
        }

    private fun uniqueName(parent: File, name: String): File {
        val candidate = File(parent, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (true) {
            val next = File(parent, "$base copy${if (index == 1) "" else " $index"}$extension")
            if (!next.exists()) return next
            index++
        }
    }

    suspend fun delete(projectId: String, relativePath: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            require(relativePath.isNotBlank()) { "Refusing to delete the project root" }
            resolve(projectId, relativePath).deleteRecursively()
        }
    }

    /** Canonical, so a shell's cwd matches the paths the explorer reports. */
    fun projectRoot(projectId: String): File = canonicalRoot(projectId)

    /**
     * Gives a brand-new project something to open, so the editor is not staring
     * at an empty tree on first launch.
     */
    suspend fun seedStarterFiles(projectId: String, projectName: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val root = paths.projectDir(projectId)
            root.mkdirs()
            File(root, "README.md").writeText(starterReadme(projectName))
            File(root, "main.py").writeText(STARTER_PYTHON)
            File(root, "src").mkdirs()
            File(root, "src/app.js").writeText(STARTER_JS)
        }
    }

    /**
     * Canonical project root, resolved once per call site.
     *
     * This must be the *same* root used for both containment checks and for
     * computing relative paths. On most devices `/data/user/0` is a symlink to
     * `/data/data`, so a canonical and a non-canonical root disagree - and a
     * relative path computed against one then fails containment against the
     * other, making every file in the project look like a traversal attempt.
     */
    private fun canonicalRoot(projectId: String): File = paths.projectDir(projectId).canonicalFile

    private fun resolve(projectId: String, relativePath: String): File {
        val root = canonicalRoot(projectId)
        val target = File(root, relativePath).canonicalFile
        val inside = target.path == root.path || target.path.startsWith(root.path + File.separator)
        if (!inside) throw SandboxError.StorageFailure("path escapes project: $relativePath")
        return target
    }

    private fun File.toNode(root: File) = FileNode(
        name = name,
        relativePath = relativeTo(root).path,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0,
    )

    /**
     * Doubles as the markdown-preview smoke test: every block type the renderer
     * claims to support appears here, so a regression is visible on first open
     * rather than discovered later.
     */
    private fun starterReadme(projectName: String) = """
        # $projectName

        Created with **tab-code**. The tree, this editor and the terminal all
        work on *real files* in this project directory.

        ## Formatting

        Inline `code`, **bold**, *italic*, and a [link](https://example.com).

        ## Lists

        - a bullet
        - another bullet

        1. first step
        2. second step

        > A blockquote, for notes worth setting apart.

        ---

        ## Code

        ```kotlin
        fun main() {
            println("hello from ${'$'}projectName")
        }
        ```

        ```mermaid
        graph TD
          A[Editor] --> B[Terminal]
          B --> C[Ubuntu sandbox]
        ```
    """.trimIndent()

    private companion object {
        const val NUL_BYTE: Byte = 0
        const val HEX_BYTES_PER_ROW = 16
        const val PRINTABLE_MIN = 0x20
        const val PRINTABLE_MAX = 0x7E

        val STARTER_PYTHON = """
            def main():
                print("hello from tab-code")


            if __name__ == "__main__":
                main()
        """.trimIndent()

        val STARTER_JS = """
            // Edit me, then press the save icon in the tab bar.
            export function greet(name) {
              return `hello ${'$'}{name}`;
            }
        """.trimIndent()
    }
}
