package dev.easyide.app.extensions.install

import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.manifest.Source
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.extensions.ExtensionVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import dev.easyide.sandbox.extensions.ExtensionId as DirId

/** A validated package waiting in staging for the user's decision on the capability sheet. */
data class StagedPackage(
    val descriptor: ExtensionDescriptor,
    val warnings: List<Diagnostic>,
    /** Unpacked package; becomes the version directory on [LocalInstaller.commit]. */
    val directory: File,
    /** The same id and version is already installed in some scope. */
    val alreadyInstalled: Boolean,
)

sealed interface StageResult {
    data class Staged(val pkg: StagedPackage) : StageResult

    /** [problems] are one line each: refusal reasons or `E_` diagnostics. */
    data class Rejected(val problems: List<String>) : StageResult
}

/**
 * "Install from folder / file" (ECO-02, registry-and-install.md sec 8.4) until the
 * registry pipeline lands: stage -> validate -> capability sheet -> version dir ->
 * atomic `current` flip -> `state.json` record -> inventory rescan (which enables and
 * registers it). Local packages are labelled "unsigned, local" and never auto-updated.
 *
 * Packages with `easyide.sandbox.install` steps are refused: those steps must run
 * visibly in an environment terminal with verify and rollback (EXT-27), which belongs to
 * the registry installer, and enabling such a pack without its toolchain would leave
 * commands that cannot work.
 */
class LocalInstaller(
    private val paths: SandboxPaths,
    private val state: ExtensionStateStore,
    private val inventory: DiskExtensionInventory,
    private val parser: ManifestParser,
    private val limits: () -> PackageLimits,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val unpacker = PackageUnpacker(limits)

    /** Staging never survives a restart: nothing there was approved. */
    suspend fun clearStaging() = withContext(io) {
        paths.extensionStagingDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun stageArchive(open: () -> InputStream): StageResult = stage { dir ->
        val archive = File(dir.parentFile, dir.name + ARCHIVE_SUFFIX)
        try {
            open().use { unpacker.unpackZip(it, archive, dir) }
        } finally {
            archive.delete()
        }
    }

    suspend fun stageFolder(root: FolderNode): StageResult = stage { dir -> unpacker.copyFolder(root, dir) }

    /** Drops a staged package the user declined. */
    suspend fun discard(pkg: StagedPackage) = withContext(io) { pkg.directory.deleteRecursively() }

    /**
     * Moves [pkg] into its scope ([envId] is required for environment-scoped packs),
     * flips `current`, records the approval of exactly the declared capabilities and
     * rescans. @throws IOException when a filesystem step fails; nothing is recorded then.
     */
    suspend fun commit(pkg: StagedPackage, envId: String?) {
        val d = pkg.descriptor
        val scope = d.scope
        require(scope == InstallScope.GLOBAL || envId != null) { "environment-scoped ${d.id} needs a target environment" }
        val targetEnv = envId.takeIf { scope == InstallScope.ENVIRONMENT }
        withContext(io) {
            val id = DirId.parse(d.id.value)
            val version = ExtensionVersion.parse(d.version.toString())
            val scopeDir = if (scope == InstallScope.GLOBAL) paths.globalExtensionsDir else paths.environmentExtensionsDir(targetEnv!!)
            val versionDir = paths.extensionVersionDir(scopeDir, id, version)
            if (versionDir.exists()) throw IOException("${d.id} ${d.version} is already installed")
            versionDir.parentFile?.mkdirs()
            move(pkg.directory, versionDir)
            val previous = paths.extensionCurrentLink(scopeDir, id).takeIf { Files.isSymbolicLink(it.toPath()) }
                ?.let { Files.readSymbolicLink(it.toPath()).fileName.toString() }
            flipCurrent(paths.extensionCurrentLink(scopeDir, id), version.value)
            // Keep the active and the previous version (rollback), drop anything older.
            paths.extensionDir(scopeDir, id).listFiles()
                ?.filter { it.isDirectory && ExtensionVersion.parseOrNull(it.name) != null && it.name != version.value && it.name != previous }
                ?.forEach { it.deleteRecursively() }
            state.update { s ->
                val prior = s.entryFor(d.id.value, scope, targetEnv)
                val entry = InstallEntry(
                    id = d.id.value, scope = scope, envId = targetEnv, source = Source.SIDELOAD, version = version.value,
                    // An update keeps its place in every tie-break.
                    installedAt = prior?.installedAt ?: clock(),
                    approvedCapabilities = d.capabilities.items.mapTo(HashSet()) { it.id },
                )
                s.copy(installs = s.installs.filterNot { it === prior } + entry)
            }
        }
        inventory.rescan()
    }

    /** Removes every version of an installed (non-built-in) package and its record. */
    suspend fun uninstall(pkg: InstalledPackage) {
        require(pkg.source != Source.BUILT_IN) { "built-in extensions cannot be uninstalled" }
        withContext(io) {
            val idDir = pkg.directory.parentFile ?: return@withContext
            val id = idDir.name
            idDir.deleteRecursively()
            state.update { s -> s.copy(installs = s.installs.filterNot { it.id == id && it.scope == pkg.scope && it.envId == pkg.envId }) }
        }
        inventory.rescan()
    }

    private suspend fun stage(fill: (File) -> Unit): StageResult = withContext(io) {
        val dir = File(paths.extensionStagingDir, UUID.randomUUID().toString())
        try {
            dir.mkdirs()
            fill(dir)
            validate(dir).also { if (it is StageResult.Rejected) dir.deleteRecursively() }
        } catch (e: IOException) {
            // PackageRefused and plain I/O failures alike: the pick could not be staged.
            dir.deleteRecursively()
            StageResult.Rejected(listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun validate(dir: File): StageResult {
        val files = when (val layout = PackageLayoutReader.read(dir, limits())) {
            is PackageLayout.Invalid -> return StageResult.Rejected(layout.errors.map(Diagnostic::toString))
            is PackageLayout.Ok -> layout.files
        }
        val parsed = when (val r = parser.parse(files)) {
            is ParseResult.Invalid -> return StageResult.Rejected(r.errors.map(Diagnostic::toString))
            is ParseResult.Ok -> r
        }
        val d = parsed.descriptor
        if (d.contributes.sandbox?.install?.isNotEmpty() == true) {
            return StageResult.Rejected(listOf(SANDBOX_STEPS_REFUSED))
        }
        if (inventory.installed.value.any { it.source == Source.BUILT_IN && it.directory.parentFile?.name == d.id.value }) {
            return StageResult.Rejected(listOf("${d.id} is built into easyIDE"))
        }
        val installed = inventory.installed.value.any { it.directory.parentFile?.name == d.id.value && it.directory.name == d.version.toString() }
        return StageResult.Staged(StagedPackage(d, parsed.warnings, dir, installed))
    }

    /** Same filesystem by construction (all under filesDir), so this is a rename. */
    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    /** `current.new -> <version>`, then rename over `current`: atomic on one filesystem. */
    private fun flipCurrent(current: File, version: String) {
        val next = File(current.parentFile, current.name + NEW_LINK_SUFFIX).toPath()
        Files.deleteIfExists(next)
        Files.createSymbolicLink(next, Paths.get(version))
        Files.move(next, current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val ARCHIVE_SUFFIX = ".easyext"
        const val NEW_LINK_SUFFIX = ".new"
        const val SANDBOX_STEPS_REFUSED =
            "This package declares easyide.sandbox install steps. Local install does not run toolchain steps; " +
                "they need the registry installer, which runs them visibly with verify and rollback."
    }
}
