package dev.easyide.sandbox.extensions

import dev.easyide.sandbox.SandboxPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Reads the on-disk shape the extension store writes (lld/registry-and-install.md
 * sec 11): `<scopeDir>/<id>/<version>/` plus a `current` symlink naming the active
 * version. Shared by the guest binds and the app's inventory so both agree on what
 * counts as an installed, active version.
 *
 * Anything that does not look exactly like an install the store wrote is skipped:
 * non-canonical names, a missing or dangling `current`, or a `current` that resolves
 * outside its own extension dir. Hygiene against accidental breakage, not a security
 * boundary (decision 0002): the directory is writable by sandboxed code.
 */
object ExtensionInstalls {

    /** An installed extension whose `current` resolves to a well-formed version dir. */
    data class Active(val id: ExtensionId, val versionDir: File) {
        val version: ExtensionVersion get() = ExtensionVersion.parse(versionDir.name)
    }

    /** Every active install in [scopeDir], sorted by id. I/O; call off the main thread. */
    fun activeIn(paths: SandboxPaths, scopeDir: File): List<Active> {
        val entries = scopeDir.listFiles() ?: return emptyList()
        return entries
            .sortedBy { it.name }
            .mapNotNull { entry ->
                val id = ExtensionId.parseOrNull(entry.name)?.takeIf { it.value == entry.name && entry.isDirectory }
                    ?: return@mapNotNull null
                activeVersionDir(paths, scopeDir, id)?.let { Active(id, it) }
            }
    }

    /** The real directory `current` points at, or null if it is not a well-formed active version. */
    fun activeVersionDir(paths: SandboxPaths, scopeDir: File, id: ExtensionId): File? {
        val link = paths.extensionCurrentLink(scopeDir, id).toPath()
        if (!Files.isSymbolicLink(link)) return null
        val (target, owner) = try {
            link.toRealPath() to paths.extensionDir(scopeDir, id).toPath().toRealPath()
        } catch (e: IOException) {
            // Dangling link: an install or rollback was interrupted mid-way.
            return null
        }
        val wellFormed = target.parent == owner &&
            Files.isDirectory(target) &&
            ExtensionVersion.parseOrNull(target.fileName.toString()) != null
        return if (wellFormed) target.toFile() else null
    }
}
