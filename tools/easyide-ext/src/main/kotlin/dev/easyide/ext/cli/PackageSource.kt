package dev.easyide.ext.cli

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
    /** cli.md sec 5.3 excludes, plus author-side files that are never package content. */
    private val DEFAULT_IGNORES = listOf(
        ".git/", "test/", "guest/", "target/", "node_modules/", "dist/",
        ".gitignore", ".easyextignore", CliConfig.PROJECT_FILE, "*.key", "*.easyext", "*.easyext.sig", ".DS_Store",
    )

    const val IGNORE_FILE = ".easyextignore"

    fun ignoreRules(dir: File): IgnoreRules {
        val file = File(dir, IGNORE_FILE)
        val extra = if (file.isFile) String(file.readBytesOrFail(), Charsets.UTF_8).lines() else emptyList()
        return IgnoreRules(DEFAULT_IGNORES + extra)
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

/**
 * The gitignore subset of cli.md sec 5.3: `*`, `**`, `/` anchoring, trailing `/` for
 * directories and `!` negation; the last matching rule wins. Paths are package-relative, and
 * directories are passed with a trailing `/`.
 */
class IgnoreRules(lines: List<String>) {
    private class Rule(val regex: Regex, val negate: Boolean, val dirOnly: Boolean)

    private val rules: List<Rule> = lines.mapNotNull { raw ->
        var p = raw.trim()
        if (p.isEmpty() || p.startsWith("#")) return@mapNotNull null
        val negate = p.startsWith("!")
        if (negate) p = p.substring(1)
        val dirOnly = p.endsWith("/")
        p = p.trimEnd('/')
        val anchored = p.contains('/')
        p = p.trimStart('/')
        if (p.isEmpty()) return@mapNotNull null
        val body = globToRegex(p)
        Rule(Regex(if (anchored) "^$body(/.*)?$" else "^(.*/)?$body(/.*)?$"), negate, dirOnly)
    }

    fun ignored(path: String): Boolean {
        val isDir = path.endsWith("/")
        val p = path.trimEnd('/')
        var result = false
        for (r in rules) {
            // A directory-only rule matches the directory itself and anything below it.
            val m = r.regex.matchEntire(p) ?: continue
            if (r.dirOnly && !isDir && m.groupValues.last().isEmpty()) continue
            result = !r.negate
        }
        return result
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }
}
