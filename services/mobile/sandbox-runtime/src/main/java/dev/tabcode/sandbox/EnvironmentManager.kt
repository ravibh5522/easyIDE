package dev.tabcode.sandbox

import dev.tabcode.sandbox.bootstrap.BootstrapSource
import dev.tabcode.sandbox.bootstrap.TarGzExtractor
import dev.tabcode.sandbox.model.EnvironmentState
import dev.tabcode.sandbox.model.SandboxBackend
import dev.tabcode.sandbox.model.SandboxEnvironment
import dev.tabcode.sandbox.store.SandboxStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Owns the lifecycle of sandbox environments: create, provision a rootfs into
 * them, and delete. Environments are shared by reference - several projects can
 * point at one environment id, which is why deletion is refused while any
 * project still uses it. See
 * docs/decision/0005-sandbox-environment-sharing-model.md.
 */
class EnvironmentManager(
    private val store: SandboxStore,
    private val paths: SandboxPaths,
    private val rootDetector: RootDetector,
    private val extractor: TarGzExtractor,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    val environments: Flow<List<SandboxEnvironment>> = store.state.map { it.environments }

    /** CHROOT is only offered when root looks available - never auto-selected. */
    fun availableBackends(): List<SandboxBackend> = buildList {
        add(SandboxBackend.PROOT)
        if (rootDetector.isRootLikelyAvailable()) add(SandboxBackend.CHROOT)
    }

    suspend fun create(
        label: String,
        backend: SandboxBackend,
        imageId: String? = null,
    ): Result<SandboxEnvironment> =
        runCatching {
            require(label.isNotBlank()) { "Environment label must not be blank" }
            if (backend == SandboxBackend.CHROOT && !rootDetector.isRootLikelyAvailable()) {
                throw SandboxError.BackendUnavailable(backend.name, "device does not appear rooted")
            }

            val now = clock()
            val environment = SandboxEnvironment(
                id = idGenerator(),
                label = label.trim(),
                backend = backend,
                state = EnvironmentState.NOT_PROVISIONED,
                createdAtEpochMs = now,
                lastUsedAtEpochMs = now,
                imageId = imageId,
            )
            withContext(ioDispatcher) {
                paths.ensureBaseDirs()
                paths.rootfsDir(environment.id).mkdirs()
            }
            store.update { it.upsertEnvironment(environment) }
            environment
        }

    /**
     * Unpacks [source] into the environment's rootfs. State moves
     * NOT_PROVISIONED -> PROVISIONING -> READY (or FAILED with a reason the UI
     * can show), so an interrupted provision is visible rather than silently
     * leaving an empty rootfs that looks usable.
     */
    suspend fun provision(environmentId: String, source: BootstrapSource): Result<SandboxEnvironment> =
        runCatching {
            val existing = store.current().environment(environmentId)
                ?: throw SandboxError.EnvironmentNotFound(environmentId)

            markState(existing, EnvironmentState.PROVISIONING, failureReason = null)

            try {
                withContext(ioDispatcher) {
                    val rootfs = paths.rootfsDir(environmentId)
                    rootfs.mkdirs()
                    source.open().use { stream -> extractor.extract(stream, rootfs) }
                }
                markState(existing, EnvironmentState.READY, failureReason = null)
            } catch (cause: Exception) {
                val reason = cause.message ?: cause::class.simpleName.orEmpty()
                markState(existing, EnvironmentState.FAILED, failureReason = reason)
                throw SandboxError.ProvisioningFailed(environmentId, reason, cause)
            }
        }

    /**
     * @throws SandboxError.EnvironmentInUse if any project still references it -
     *   callers should reassign those projects first.
     */
    suspend fun delete(environmentId: String): Result<Unit> = runCatching {
        val state = store.current()
        val environment = state.environment(environmentId)
            ?: throw SandboxError.EnvironmentNotFound(environmentId)

        val dependents = state.projectsUsing(environmentId)
        if (dependents.isNotEmpty()) {
            throw SandboxError.EnvironmentInUse(environmentId, dependents.map { it.name })
        }

        withContext(ioDispatcher) {
            paths.environmentDir(environment.id).deleteRecursively()
        }
        store.update { it.removeEnvironment(environmentId) }
    }

    /**
     * Records the outcome of a [LinuxEnvironment] install. That path unpacks
     * the rootfs itself, so without this an environment stays
     * `NOT_PROVISIONED` in the store forever and every screen that shows its
     * state lies about a perfectly working sandbox.
     */
    suspend fun markProvisioned(environmentId: String, failureReason: String? = null) {
        store.update { state ->
            val environment = state.environment(environmentId) ?: return@update state
            state.upsertEnvironment(
                environment.copy(
                    state = if (failureReason == null) EnvironmentState.READY else EnvironmentState.FAILED,
                    failureReason = failureReason,
                )
            )
        }
    }

    suspend fun touch(environmentId: String) {
        store.update { state ->
            val environment = state.environment(environmentId) ?: return@update state
            state.upsertEnvironment(environment.copy(lastUsedAtEpochMs = clock()))
        }
    }

    private suspend fun markState(
        environment: SandboxEnvironment,
        state: EnvironmentState,
        failureReason: String?,
    ): SandboxEnvironment {
        val updated = environment.copy(state = state, failureReason = failureReason)
        store.update { it.upsertEnvironment(updated) }
        return updated
    }
}
