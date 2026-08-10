package dev.tabcode.sandbox

import java.io.File

/**
 * Every filesystem path the sandbox layer uses, derived from one root. Nothing
 * else in the codebase should build sandbox paths by string concatenation.
 *
 * Layout (all inside app-private storage, which is exempt from scoped-storage
 * restrictions - see docs/sandbox-runtime/arch.md SS7):
 *
 *     <root>/environments/<envId>/rootfs     one Linux userland per environment
 *     <root>/projects/<projectId>             source dirs, deliberately outside
 *                                              every rootfs so they can be
 *                                              bind-mounted into any environment
 *     <root>/run                               sockets (credential helper, agents)
 *
 * Keeping projects outside the rootfs is what makes an environment shareable
 * and reassignable - see docs/decision/0005-sandbox-environment-sharing-model.md.
 */
class SandboxPaths(private val root: File) {

    val environmentsDir: File get() = File(root, ENVIRONMENTS_DIR)

    val projectsDir: File get() = File(root, PROJECTS_DIR)

    val runtimeDir: File get() = File(root, RUNTIME_DIR)

    /**
     * Downloaded distro tarballs, shared by every environment on the device.
     * Deliberately not per-environment: a second environment must not re-pay a
     * ~30 MB download for an image already on disk.
     */
    val imageCacheDir: File get() = File(root, IMAGE_CACHE_DIR)

    /** Cache file for one image, named from its URL so images never collide. */
    fun cachedImage(imageId: String): File = File(imageCacheDir, "$imageId.tar.gz")

    fun environmentDir(environmentId: String): File = File(environmentsDir, environmentId)

    fun rootfsDir(environmentId: String): File = File(environmentDir(environmentId), ROOTFS_DIR)

    fun projectDir(projectId: String): File = File(projectsDir, projectId)

    /** Where a project appears from inside the sandbox, once bind-mounted. */
    fun guestProjectPath(): String = GUEST_WORKSPACE

    /** Creates the fixed directory skeleton. Safe to call repeatedly. */
    fun ensureBaseDirs() {
        listOf(environmentsDir, projectsDir, runtimeDir, imageCacheDir).forEach { dir ->
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw SandboxError.StorageFailure("create ${dir.absolutePath}")
            }
        }
    }

    private companion object {
        const val ENVIRONMENTS_DIR = "environments"
        const val PROJECTS_DIR = "projects"
        const val RUNTIME_DIR = "run"
        const val IMAGE_CACHE_DIR = "images"
        const val ROOTFS_DIR = "rootfs"
        const val GUEST_WORKSPACE = "/workspace"
    }
}
