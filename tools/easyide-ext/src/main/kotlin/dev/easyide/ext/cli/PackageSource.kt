package dev.easyide.ext.cli

import dev.easyide.extensions.authoring.PackageIgnores
import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.PackageFiles
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.PackagePaths
import dev.easyide.extensions.manifest.ZipFormatException
import dev.easyide.extensions.manifest.ZipSymlinks
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * What goes into a package, and how `validate` reads either a source folder or a built
 * `.easyext`. Both paths end in the shared `PackageLayoutReader`, the same second-line check
 * the app runs on disk after unpacking.
 */
object PackageSource {
    const val IGNORE_FILE = PackageIgnores.IGNORE_FILE

    fun ignoreRules(dir: File): IgnoreRules {
        val file = File(dir, IGNORE_FILE)
        return PackageIgnores.rules(if (file.isFile) String(file.readBytesOrFail(), Charsets.UTF_8) else null)
    }

    /** A source folder as the would-be package: ignored paths dropped, layout rules applied. */
    fun readFolder(dir: File, limits: PackageLimits): PackageLayout {
        val rules = ignoreRules(dir)
        return PackageLayoutReader.read(dir, limits, rules::ignored)
    }

    /**
     * Unpacks [archive] into [dest] under the install rules: normalised names only, no symlink
     * entries, sizes counted while copying (never trusted from headers). Returns entry-level
     * errors; the unpacked tree then goes through [PackageLayoutReader] like an install.
     */
    fun unpack(archive: File, dest: File, limits: PackageLimits): List<Diagnostic> {
        val errors = ArrayList<Diagnostic>()
        fun err(code: String, path: String, msg: String) { errors += Diagnostic.error(code, "", msg, path) }
        if (archive.length() > limits.packageBytes) {
            err(DiagnosticCode.PACKAGE_TOO_LARGE, archive.name, "package is ${archive.length()} bytes, limit ${limits.packageBytes}")
            return errors
        }
        try {
            ZipSymlinks.find(archive).forEach { err(DiagnosticCode.PACKAGE_SYMLINK, it, "symbolic links are not allowed") }
            if (errors.isNotEmpty()) return errors
            var total = 0L
            ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val path = PackagePaths.normalize(entry.name)
                    if (path == null || path != entry.name) { err(DiagnosticCode.PACKAGE_PATH, entry.name, "entry name must be relative, '/'-separated and without '.' or '..' segments"); continue }
                    val target = File(dest, path)
                    target.parentFile?.mkdirs()
                    if (target.exists()) { err(DiagnosticCode.PACKAGE_DUPLICATE, path, "duplicate entry"); continue }
                    val budget = minOf(limits.fileBytes, limits.unpackedBytes - total)
                    val written = zip.getInputStream(entry).use { copyBounded(it, target, budget) }
                    if (written < 0) { err(DiagnosticCode.PACKAGE_FILE_TOO_LARGE, path, "exceeds the size limit ($budget bytes)"); return errors }
                    total += written
                }
            }
        } catch (e: ZipFormatException) {
            err(DiagnosticCode.PACKAGE_IO, archive.name, e.message ?: "malformed zip")
        } catch (e: ZipException) {
            err(DiagnosticCode.PACKAGE_IO, archive.name, "not a valid .easyext archive: ${e.message}")
        } catch (e: IOException) {
            ioFailure("cannot read ${archive.path}: ${e.message}", archive.path)
        }
        return errors
    }

    /** Bytes written, or -1 once [limit] is passed (the partial file is left for the temp dir cleanup). */
    private fun copyBounded(input: InputStream, target: File, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        target.outputStream().use { out ->
            while (true) {
                val r = input.read(buf)
                if (r < 0) return n
                n += r
                if (n > limit) return -1
                out.write(buf, 0, r)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return n
    }

    fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("easyide-ext").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The files of an [PackageLayout.Ok], or its errors. */
    fun files(layout: PackageLayout, out: MutableList<Diagnostic>): PackageFiles? = when (layout) {
        is PackageLayout.Ok -> layout.files
        is PackageLayout.Invalid -> { out += layout.errors; null }
    }
}

/** Shared with the app's developer installs from a folder (`services/shared/extension-schema`). */
typealias IgnoreRules = dev.easyide.extensions.authoring.IgnoreRules
