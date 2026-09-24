package dev.easyide.app.extensions.install

import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.extensions.ExtensionInstalls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What is installed, read from disk: built-ins unpacked from the APK, global installs and
 * every environment's installs (`<id>/current` -> version dir, registry-and-install.md
 * sec 11), joined with `state.json` for source, install time and approvals.
 *
 * A version directory with no matching state entry (copied in by hand, or its record
 * lost) is listed as a sideload with no approvals, so it stays disabled until approved
 * rather than running with capabilities nobody saw.
 */
class DiskExtensionInventory(
    private val paths: SandboxPaths,
    private val builtIns: () -> List<File>,
    private val state: ExtensionStateStore,
    private val io: CoroutineDispatcher,
) : ExtensionInventory {

    private val flow = MutableStateFlow<List<InstalledPackage>>(emptyList())
    private val scanLock = Mutex()
    override val installed: StateFlow<List<InstalledPackage>> = flow.asStateFlow()

    /** Re-reads disk and state; call after any install, uninstall or flip. */
    suspend fun rescan() = scanLock.withLock {
        flow.value = withContext(io) { scan() }
    }

    override suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean) {
        withContext(io) {
            state.update { s -> s.copy(crashDisabled = if (disabled) s.crashDisabled + id.value else s.crashDisabled - id.value) }
        }
        rescan()
    }

    /** Pure over the filesystem and [state]; visible for tests. */
    internal fun scan(): List<InstalledPackage> {
        val s = state.read()
        val builtIn = builtIns().map { dir ->
            // `<root>/<id>/<stamp>`: the id is the parent directory's name.
            InstalledPackage(
                directory = dir, scope = InstallScope.GLOBAL, envId = null, source = Source.BUILT_IN,
                installedAt = BUILT_IN_INSTALLED_AT, approvedCapabilities = emptySet(), revoked = false,
                crashDisabled = dir.parentFile?.name in s.crashDisabled,
            )
        }
        val global = installsIn(paths.globalExtensionsDir, InstallScope.GLOBAL, null, s)
        val perEnvironment = paths.environmentsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .flatMap { env -> installsIn(paths.environmentExtensionsDir(env.name), InstallScope.ENVIRONMENT, env.name, s) }
        return builtIn + global + perEnvironment
    }

    private fun installsIn(scopeDir: File, scope: InstallScope, envId: String?, s: ExtensionState): List<InstalledPackage> =
        ExtensionInstalls.activeIn(paths, scopeDir).map { active ->
            val id = active.id.value
            val entry = s.entryFor(id, scope, envId)?.takeIf { it.version == active.versionDir.name }
            InstalledPackage(
                directory = active.versionDir,
                scope = scope,
                envId = envId,
                source = entry?.source ?: Source.SIDELOAD,
                installedAt = entry?.installedAt ?: active.versionDir.lastModified(),
                approvedCapabilities = entry?.approvedCapabilities.orEmpty(),
                revoked = false,
                crashDisabled = id in s.crashDisabled,
            )
        }

    private companion object {
        /** Built-ins sort first in every tie-break (earliest installed wins). */
        const val BUILT_IN_INSTALLED_AT = 0L
    }
}
