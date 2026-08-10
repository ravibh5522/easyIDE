package dev.easyide.sandbox.model

/**
 * Which isolation mechanism an environment runs under.
 * See docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md -
 * PROOT is the default everywhere; CHROOT is an informed opt-in on rooted
 * devices only, and is strictly more permissive, not safer.
 */
enum class SandboxBackend {
    PROOT,
    CHROOT,
}

/** Provisioning lifecycle of an environment's rootfs. */
enum class EnvironmentState {
    /** Record exists, rootfs not yet extracted. */
    NOT_PROVISIONED,
    PROVISIONING,
    READY,
    /** Provisioning failed; [SandboxEnvironment.failureReason] explains why. */
    FAILED,
}

/**
 * A provisioned Linux userland (rootfs plus whatever the user installed into
 * it). Environments are shareable: several projects may reference the same
 * environment id so they share one toolchain install.
 * See docs/decision/0005-sandbox-environment-sharing-model.md.
 */
data class SandboxEnvironment(
    val id: String,
    val label: String,
    val backend: SandboxBackend,
    val state: EnvironmentState,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val failureReason: String? = null,
    /**
     * Which [SandboxImage] the user picked when creating this environment.
     * Stored because provisioning is deferred - the rootfs is only unpacked on
     * first use, long after the choice was made. Null on environments created
     * before the catalog existed; those fall back to the base image.
     */
    val imageId: String? = null,
)

/**
 * A source directory the user works in. Projects live outside every rootfs and
 * are bind-mounted into whichever environment they are attached to, which is
 * what makes [environmentId] cheap to reassign.
 */
data class ProjectRecord(
    val id: String,
    val name: String,
    val environmentId: String,
    val createdAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long,
    /**
     * A SAF tree URI (as a string) the project's files are mirrored to, or
     * null when the project lives only in app-private storage. The mirror is
     * one-directional and best-effort - see
     * [dev.easyide.sandbox.external.ExternalFolderSync] for why the app-private
     * copy, not this folder, is what the sandbox actually runs against.
     */
    val externalFolderUri: String? = null,
)
