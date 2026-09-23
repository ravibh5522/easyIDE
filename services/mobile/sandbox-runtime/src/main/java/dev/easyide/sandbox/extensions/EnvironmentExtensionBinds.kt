package dev.easyide.sandbox.extensions

import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.backend.GuestBind
import dev.easyide.sandbox.backend.GuestBindSource
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Makes each installed ENVIRONMENT extension's active version visible at
 * `/opt/easyide/extensions/<id>` in every process launched into that
 * environment (lld/registry-and-install.md sec 11.2).
 *
 * The `current` link is resolved here, on the host, and the version dir itself
 * is bound: a later flip then affects only processes started after it, which is
 * the documented contract, instead of changing files under a running server.
 *
 * Anything on disk that does not look exactly like an install the store wrote
 * is skipped rather than bound: non-canonical names, a missing or dangling
 * `current`, or a `current` that resolves outside its own extension dir. The
 * directory is writable by sandboxed code, so this is hygiene against
 * accidental breakage - not a security boundary (decision 0002).
 *
 * @param isEnabled user/system enablement, owned by the extensions layer. The
 *   default binds every installed extension, which is correct until that
 *   layer supplies a real predicate.
 */
class EnvironmentExtensionBinds(
    private val paths: SandboxPaths,
    private val isEnabled: (environmentId: String, id: ExtensionId) -> Boolean = { _, _ -> true },
) : GuestBindSource {

    override fun bindsFor(environmentId: String): List<GuestBind> {
        val scopeDir = paths.environmentExtensionsDir(environmentId)
        val entries = scopeDir.listFiles() ?: return emptyList()
        return entries
            .sortedBy { it.name }
            .mapNotNull { entry ->
                val id = ExtensionId.parseOrNull(entry.name)
                    ?.takeIf { it.value == entry.name && entry.isDirectory }
                    ?.takeIf { isEnabled(environmentId, it) }
                    ?: return@mapNotNull null
                activeVersionDir(scopeDir, id)?.let { GuestBind(it, paths.guestExtensionPath(id)) }
            }
    }

    /** The real directory `current` points at, or null if it is not a well-formed active version. */
    private fun activeVersionDir(scopeDir: File, id: ExtensionId): File? {
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
