package dev.easyide.sandbox

import dev.easyide.sandbox.extensions.ExtensionId
import dev.easyide.sandbox.extensions.ExtensionVersion
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
 *     <root>/extensions/global/<id>/<version>  global extension installs
 *     <root>/extensions/staging                downloads and unpacks awaiting verification
 *     <root>/extensions/state.json             approvals, pins, system disables
 *     <root>/extensions/builtin/<id>/<stamp>   built-in extensions unpacked from the APK
 *     <root>/extensions/activation-journal.json crash journal of the extension runtime
 *     <root>/extensions/registry/<registryId>  last verified registry index (+ sigs, publishers, meta)
 *     <root>/extensions/cache/<sha256>.easyext content-addressed offline package cache
 *     <root>/environments/<envId>/extensions/<id>/<version>
 *                                              environment installs, deleted with the env
 *
 * Extension layout per docs/extension-sdk/lld/registry-and-install.md sec 11.
 * It is organisation, not protection: everything here is readable and
 * writable by any process running as the app UID, sandboxed code included
 * (decision 0002).
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

    /**
     * Android's own `/dev`, `/proc`, `/sys`, bound into every guest as they are. A guest path
     * under one of them has no file in the rootfs, so path mapping (LSP) must not look there.
     */
    val guestPassthroughMounts: List<String> get() = PASSTHROUGH_MOUNTS

    val extensionsDir: File get() = File(root, EXTENSIONS_DIR)

    /** Scope dir for GLOBAL installs: themes, snippets, grammars, WASM-only packs. */
    val globalExtensionsDir: File get() = File(extensionsDir, GLOBAL_EXTENSIONS_DIR)

    /**
     * Where packages are downloaded and unpacked before they are verified and
     * moved into a scope dir. Same filesystem as every scope dir, so the final
     * move is a `rename(2)`, never a copy.
     */
    val extensionStagingDir: File get() = File(extensionsDir, STAGING_DIR)

    /** Approvals, TOFU pins and system disables; not user enablement (that is `extensions.disabled`). */
    val extensionStateFile: File get() = File(extensionsDir, STATE_FILE)

    /**
     * Built-in extensions shipped in the APK, unpacked once per APK build so
     * the runtime reads them through the same directory reader as any install.
     */
    val builtInExtensionsDir: File get() = File(extensionsDir, BUILTIN_DIR)

    /** Last verified copy of each configured registry (registry-and-install.md sec 3.2). */
    val extensionRegistriesDir: File get() = File(extensionsDir, REGISTRY_DIR)

    /**
     * One registry's verified index, signatures, publisher files and `meta.json`. The caller
     * validates [registryId] (a lowercase slug), so it cannot name a path outside this dir.
     */
    fun extensionRegistryDir(registryId: String): File = File(extensionRegistriesDir, registryId)

    /** Offline package cache, content-addressed by the package's sha256 (sec 12). */
    val extensionPackageCacheDir: File get() = File(extensionsDir, PACKAGE_CACHE_DIR)

    /** The runtime's crash journal (extension-runtime.md sec 5.3). */
    val extensionJournalFile: File get() = File(extensionsDir, JOURNAL_FILE)

    /**
     * Scope dir for ENVIRONMENT installs (packs with sandbox steps or language
     * servers). Inside [environmentDir], so deleting the environment deletes them.
     */
    fun environmentExtensionsDir(environmentId: String): File =
        File(environmentDir(environmentId), ENVIRONMENT_EXTENSIONS_DIR)

    /**
     * One extension's directory inside [scopeDir] ([globalExtensionsDir] or an
     * [environmentExtensionsDir]). Traversal-safe by construction: [ExtensionId]
     * cannot hold a separator or a `..`.
     */
    fun extensionDir(scopeDir: File, id: ExtensionId): File = File(scopeDir, id.value)

    fun extensionVersionDir(scopeDir: File, id: ExtensionId, version: ExtensionVersion): File =
        File(extensionDir(scopeDir, id), version.value)

    /** Symlink to the active version dir, flipped atomically on install/update/rollback. */
    fun extensionCurrentLink(scopeDir: File, id: ExtensionId): File =
        File(extensionDir(scopeDir, id), CURRENT_LINK)

    /** Where an environment extension's active version appears inside the guest (`${extensionPath}`). */
    fun guestExtensionPath(id: ExtensionId): String = "$GUEST_EXTENSIONS/${id.value}"

    /** Creates the fixed directory skeleton. Safe to call repeatedly. */
    fun ensureBaseDirs() {
        listOf(
            environmentsDir, projectsDir, runtimeDir, imageCacheDir,
            globalExtensionsDir, extensionStagingDir,
        ).forEach { dir ->
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw SandboxError.StorageFailure("create ${dir.absolutePath}")
            }
        }
    }

    internal companion object {
        val PASSTHROUGH_MOUNTS = listOf("/dev", "/proc", "/sys")
        const val ENVIRONMENTS_DIR = "environments"
        const val PROJECTS_DIR = "projects"
        const val RUNTIME_DIR = "run"
        const val IMAGE_CACHE_DIR = "images"
        const val ROOTFS_DIR = "rootfs"
        const val GUEST_WORKSPACE = "/workspace"
        const val EXTENSIONS_DIR = "extensions"
        const val GLOBAL_EXTENSIONS_DIR = "global"
        const val STAGING_DIR = "staging"
        const val STATE_FILE = "state.json"
        const val BUILTIN_DIR = "builtin"
        const val REGISTRY_DIR = "registry"
        const val PACKAGE_CACHE_DIR = "cache"
        const val JOURNAL_FILE = "activation-journal.json"
        const val ENVIRONMENT_EXTENSIONS_DIR = "extensions"
        const val CURRENT_LINK = "current"
        const val GUEST_EXTENSIONS = "/opt/easyide/extensions"
    }
}
