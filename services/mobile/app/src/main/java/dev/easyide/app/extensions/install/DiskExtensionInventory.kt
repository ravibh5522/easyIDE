package dev.easyide.app.extensions.install

import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.extensions.ExtensionInstalls
import dev.easyide.sandbox.extensions.ExtensionVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

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
    private val retainedFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val scanLock = Mutex()
    override val installed: StateFlow<List<InstalledPackage>> = flow.asStateFlow()

    /**
     * The version a rollback would flip to, keyed by the active version dir's absolute path
     * ([InstalledPackage.directory]); installs with nothing retained are absent.
     */
    val retained: StateFlow<Map<String, String>> = retainedFlow.asStateFlow()

    /** Re-reads disk and state; call after any install, uninstall or flip. */
    suspend fun rescan() = scanLock.withLock {
        val (packages, previous) = withContext(io) {
            val list = scan()
            val s = state.read()
            list to list.filter { it.source != Source.BUILT_IN }.mapNotNull { pkg ->
                val idDir = pkg.directory.parentFile ?: return@mapNotNull null
                retainedPrevious(idDir, pkg.directory.name, s.entryFor(idDir.name, pkg.scope, pkg.envId))
                    ?.let { pkg.directory.absolutePath to it.version }
            }.toMap()
        }
        flow.value = packages
        retainedFlow.value = previous
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

/**
 * The version a rollback of the install in [idDir] whose `current` is [current] flips to,
 * with the capabilities approved for it; null when there is none (registry-and-install.md
 * sec 10). In order:
 * - [entry] records a different version whose dir exists: a flip was interrupted before
 *   `state.json` was written, so "back" is the recorded version with its approvals;
 * - the recorded [InstallEntry.previous], while its dir is retained;
 * - with no record of one (state written before rollback existed, or no entry), the single
 *   other version dir, approved for nothing, so it needs approval like any sideload.
 */
internal fun retainedPrevious(idDir: File, current: String, entry: InstallEntry?): RetainedVersion? {
    fun retained(version: String) = version != current && ExtensionVersion.parseOrNull(version) != null &&
        File(idDir, version).let { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
    if (entry != null && entry.version != current && retained(entry.version)) {
        return RetainedVersion(entry.version, entry.approvedCapabilities)
    }
    val recorded = entry?.takeIf { it.version == current }?.previous
    if (recorded != null) return recorded.takeIf { retained(it.version) }
    return idDir.list().orEmpty().filter(::retained).singleOrNull()?.let { RetainedVersion(it, emptySet()) }
}
