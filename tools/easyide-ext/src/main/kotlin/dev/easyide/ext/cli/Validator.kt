package dev.easyide.ext.cli

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageFiles
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.manifest.Severity
import dev.easyide.extensions.schema.BuiltInCommands
import dev.easyide.extensions.schema.ManifestSchema
import java.io.File
import java.nio.ByteBuffer

/** Outcome of validating one package: the descriptor when it loads, and every finding. */
class Validation(val descriptor: ExtensionDescriptor?, val diagnostics: List<Diagnostic>, val files: PackageFiles?) {
    val errors: Int get() = diagnostics.count { it.severity == Severity.ERROR }
    val warnings: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    fun passes(strict: Boolean): Boolean = errors == 0 && (!strict || warnings == 0)
}

/**
 * cli.md sec 5.2. Package layout and limits, then the app's own `ManifestParser` (schema,
 * references, when-clauses, capability audit, scope, content), then CLI-only checks: a
 * light WASM header check and, with `--strict`, registry-publish readiness.
 *
 * WASM static validation (imports, exports, memory, metering) runs in the app's `:ext-wasm`
 * host, which stays under the app licence (decision 0015), so the CLI checks the module
 * header here and the full check happens at install.
 */
class Validator(private val apiVersion: SemVer = AppApi.VERSION, private val limits: PackageLimits = PackageLimits.DEFAULT) {

    fun validateFolder(dir: File, strict: Boolean): Validation {
        val diags = ArrayList<Diagnostic>()
        val files = PackageSource.files(PackageSource.readFolder(dir, limits), diags) ?: return Validation(null, diags, null)
        return validateFiles(files, strict, diags)
    }

    /** [dest] receives the unpacked archive and must outlive the returned files. */
    fun validateArchive(archive: File, dest: File, strict: Boolean): Validation {
        val diags = ArrayList(PackageSource.unpack(archive, dest, limits))
        if (diags.isNotEmpty()) return Validation(null, diags, null)
        val files = PackageSource.files(dev.easyide.extensions.manifest.PackageLayoutReader.read(dest, limits), diags)
            ?: return Validation(null, diags, null)
        return validateFiles(files, strict, diags)
    }

    private fun validateFiles(files: PackageFiles, strict: Boolean, diags: MutableList<Diagnostic>): Validation {
        val parser = ManifestParser(ManifestSchema.validator, ParseOptions(apiVersion = apiVersion, limits = limits, builtInCommands = BuiltInCommands.IDS))
        val descriptor = when (val r = parser.parse(files)) {
            is ParseResult.Ok -> { diags += r.warnings; r.descriptor }
            is ParseResult.Invalid -> { diags += r.errors; diags += r.warnings; null }
        }
        if (descriptor != null) {
            descriptor.wasm?.let { diags += wasmHeader(files, it.module.path) }
            if (strict) diags += publishReadiness(files, descriptor)
        }
        return Validation(descriptor, diags.sortedWith(ORDER), files)
    }

    private fun wasmHeader(files: PackageFiles, path: String): List<Diagnostic> {
        val bytes = files.read(path)
        val ok = bytes.size >= 8 && bytes.copyOfRange(0, 4).contentEquals(WASM_MAGIC) &&
            ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int == 1
        return if (ok) emptyList() else listOf(cliError(CliCode.WASM_MODULE, "not a WebAssembly 1.0 binary module", path))
    }

    /** sec 5.2 step 10: what the registry requires beyond what an install accepts. */
    private fun publishReadiness(files: PackageFiles, d: ExtensionDescriptor): List<Diagnostic> = buildList {
        val names = files.list().map { it.lowercase() }
        if ("readme.md" !in names) add(cliWarning(CliCode.README_MISSING, "README.md is required to publish", "README.md"))
        if (names.none { it == "license" || it == "license.md" || it == "license.txt" }) {
            add(cliWarning(CliCode.LICENSE_MISSING, "a LICENSE file is required to publish", "LICENSE"))
        }
        if (d.license.isNullOrBlank()) add(Diagnostic.warning(CliCode.LICENSE_FIELD, "/license", "an SPDX license expression is required to publish"))
        d.icon?.let { icon -> pngSize(files.read(icon.path))?.let { (w, h) ->
            if (w != h || (w != 128 && w != 256)) add(cliWarning(CliCode.ICON_SIZE, "icon is ${w}x$h; use a square 128x128 or 256x256 PNG", icon.path))
        } ?: add(cliWarning(CliCode.ICON_SIZE, "icon is not a PNG", icon.path)) }
    }

    /** Width and height from a PNG IHDR chunk, or null for anything else. */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || !b.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return null
        val buf = ByteBuffer.wrap(b)
        return buf.getInt(16) to buf.getInt(20)
    }

    companion object {
        private val WASM_MAGIC = byteArrayOf(0, 0x61, 0x73, 0x6d)
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        /** Errors first, then by file and pointer, so output is stable across runs. */
        private val ORDER = compareBy<Diagnostic>({ it.severity != Severity.ERROR }, { it.file }, { it.pointer }, { it.code })
    }
}
