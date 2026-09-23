package dev.easyide.extensions.manifest

import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.int
import dev.easyide.extensions.settings.SettingsPort
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** The files of one package; paths are relative and `/`-separated (extension-runtime.md sec 2). */
interface PackageFiles {
    fun exists(path: String): Boolean

    /** I/O boundary: throws IOException. */
    fun read(path: String): ByteArray

    fun list(): List<String>

    /** Size in bytes of an existing [path]. */
    fun size(path: String): Long

    /** Absolute host path of [path], for readers in `:app` (grammars, themes, icons). */
    fun hostPath(path: String): String

    /** Host directory of the package root. */
    val root: String
}

/** sdk-reference size limits, from the `extensions.limits.*` / `extensions.wasm.maxModuleMb` settings. */
data class PackageLimits(val fileBytes: Long, val unpackedBytes: Long, val packageBytes: Long, val wasmModuleBytes: Long) {
    companion object {
        val DEFAULT = PackageLimits(
            ExtensionSettings.LIMITS_FILE_MB.default * ExtensionSettings.MB,
            ExtensionSettings.LIMITS_UNPACKED_MB.default * ExtensionSettings.MB,
            ExtensionSettings.LIMITS_PACKAGE_MB.default * ExtensionSettings.MB,
            ExtensionSettings.WASM_MAX_MODULE_MB.default * ExtensionSettings.MB,
        )

        fun from(settings: SettingsPort) = PackageLimits(
            settings.int(ExtensionSettings.LIMITS_FILE_MB) * ExtensionSettings.MB,
            settings.int(ExtensionSettings.LIMITS_UNPACKED_MB) * ExtensionSettings.MB,
            settings.int(ExtensionSettings.LIMITS_PACKAGE_MB) * ExtensionSettings.MB,
            settings.int(ExtensionSettings.WASM_MAX_MODULE_MB) * ExtensionSettings.MB,
        )
    }
}

/** Path rules shared by the layout reader and manifest file references. */
object PackagePaths {
    /** Derived files the runtime writes into a version directory; never package content. */
    val RUNTIME_FILES: Set<String> = setOf(".descriptor.json")

    private val ARCHIVE_SUFFIXES = listOf(".zip", ".easyext", ".vsix", ".jar", ".apk", ".aar", ".tar", ".tgz", ".gz", ".xz", ".bz2", ".7z", ".rar")

    /**
     * Normalises a manifest reference (`./syntaxes/a.json` -> `syntaxes/a.json`); null when it
     * is absolute, uses `\`, escapes the package with `..`, or is empty.
     */
    fun normalize(ref: String): String? {
        if (ref.isEmpty() || ref.startsWith("/") || ref.contains('\\') || ref.contains('\u0000')) return null
        if (ref.length >= 2 && ref[1] == ':') return null // Windows drive
        val parts = ArrayList<String>()
        for (seg in ref.split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> return null
                else -> parts += seg
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    fun isArchive(path: String): Boolean = ARCHIVE_SUFFIXES.any { path.lowercase().endsWith(it) }
}

sealed interface PackageLayout {
    data class Ok(val files: PackageFiles) : PackageLayout
    data class Invalid(val errors: List<Diagnostic>) : PackageLayout
}

/**
 * Reads an unpacked extension directory (installed version dir or a dev folder) and applies
 * the sdk-reference layout rules: no symlinks, no nested archives, no case-insensitive
 * duplicates, per-file and total size limits. Zip-level checks (entry names, compressed
 * size) belong to the installer; this is the second line for what is already on disk.
 */
object PackageLayoutReader {

    /** I/O boundary: filesystem errors become [DiagnosticCode.PACKAGE_IO]. */
    fun read(root: File, limits: PackageLimits): PackageLayout {
        val errors = ArrayList<Diagnostic>()
        val files = LinkedHashMap<String, File>()
        val seenLower = HashMap<String, String>()
        var total = 0L
        val rootPath = root.toPath()
        if (!Files.isDirectory(rootPath)) {
            return PackageLayout.Invalid(listOf(Diagnostic.error(DiagnosticCode.PACKAGE_IO, "", "package directory not found: ${root.name}", "")))
        }
        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != rootPath && attrs.isSymbolicLink) {
                        errors += layoutError(DiagnosticCode.PACKAGE_SYMLINK, rel(dir), "symbolic links are not allowed")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val path = rel(file)
                    when {
                        path in PackagePaths.RUNTIME_FILES -> Unit
                        attrs.isSymbolicLink -> errors += layoutError(DiagnosticCode.PACKAGE_SYMLINK, path, "symbolic links are not allowed")
                        !attrs.isRegularFile -> errors += layoutError(DiagnosticCode.PACKAGE_PATH, path, "not a regular file")
                        PackagePaths.isArchive(path) -> errors += layoutError(DiagnosticCode.PACKAGE_NESTED_ARCHIVE, path, "nested archives are not allowed")
                        else -> {
                            val clash = seenLower.putIfAbsent(path.lowercase(), path)
                            if (clash != null) errors += layoutError(DiagnosticCode.PACKAGE_DUPLICATE, path, "differs from '$clash' only in case")
                            if (attrs.size() > limits.fileBytes) {
                                errors += layoutError(DiagnosticCode.PACKAGE_FILE_TOO_LARGE, path, "file is ${attrs.size()} bytes, limit ${limits.fileBytes}")
                            }
                            total += attrs.size()
                            files[path] = file.toFile()
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                private fun rel(p: Path): String = rootPath.relativize(p).joinToString("/")
            })
        } catch (e: IOException) {
            return PackageLayout.Invalid(listOf(Diagnostic.error(DiagnosticCode.PACKAGE_IO, "", "cannot read package: ${e.message}", "")))
        }
        if (total > limits.unpackedBytes) {
            errors += layoutError(DiagnosticCode.PACKAGE_TOO_LARGE, "", "unpacked size $total bytes exceeds ${limits.unpackedBytes}")
        }
        return if (errors.isEmpty()) PackageLayout.Ok(DirectoryPackage(root.absolutePath, files)) else PackageLayout.Invalid(errors)
    }

    private fun layoutError(code: String, path: String, message: String) = Diagnostic.error(code, "", message, path)

    private class DirectoryPackage(override val root: String, private val files: Map<String, File>) : PackageFiles {
        override fun exists(path: String): Boolean = path in files
        override fun read(path: String): ByteArray = (files[path] ?: throw IOException("not in package: $path")).readBytes()
        override fun list(): List<String> = files.keys.sorted()
        override fun size(path: String): Long = files[path]?.length() ?: 0L
        override fun hostPath(path: String): String = File(root, path).absolutePath
    }
}
