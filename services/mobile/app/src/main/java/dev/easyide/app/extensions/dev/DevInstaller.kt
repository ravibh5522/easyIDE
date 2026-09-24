package dev.easyide.app.extensions.dev

import dev.easyide.app.extensions.install.FileFolder
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.extensions.install.StageResult
import dev.easyide.app.extensions.install.StagedPackage
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.MANIFEST_FILE
import dev.easyide.extensions.manifest.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A developer install waiting on the capability sheet (Extensions screen). */
data class DevPending(val pkg: StagedPackage, val reason: PromptReason, val envId: String?, val added: Set<String>)

sealed interface DevOutcome {
    data object Off : DevOutcome
    data class Refused(val reason: String) : DevOutcome
    data class Pending(val pending: DevPending) : DevOutcome
    data class Installed(val id: String, val version: String) : DevOutcome
}

/**
 * Developer installs (lld/cli.md sec 5.7): stages a pushed archive or a project folder through
 * the normal [LocalInstaller] pipeline under a per-reload version ([DevReload.devVersion]),
 * then either commits it as [Source.DEV] at once (same capabilities as the running developer
 * install) or parks it in [pending] for the capability sheet ([DevReload.decide]). Every
 * outcome is an Extension Log line; [notify] shows the short form (a toast), since the author
 * is usually looking at another screen. Requests are handled one at a time.
 */
class DevInstaller(
    private val installer: LocalInstaller,
    private val installed: () -> List<InstalledPackage>,
    private val developerMode: suspend () -> Boolean,
    private val log: (LogEntry) -> Unit,
    private val notify: (String) -> Unit,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val pendingState = MutableStateFlow<DevPending?>(null)
    private var lastStamp = 0L

    /** At most one: a newer request replaces (and discards) the one waiting. */
    val pending: StateFlow<DevPending?> = pendingState.asStateFlow()

    /** The open workspace's environment: the default target of a new environment pack. */
    @Volatile var currentEnv: String? = null

    /** The adb path: `<inbox>/<id>.easyext`, consumed whatever the outcome. */
    suspend fun fromInbox(inbox: File?, id: String?): DevOutcome = lock.withLock {
        if (!developerMode()) return@withLock refuse(id, "dev reload ignored: developer mode (extensions.developerMode) is off")
        if (inbox == null) return@withLock refuse(id, "dev reload failed: shared storage is unavailable")
        when (val l = DevReload.locateInbox(inbox, id)) {
            is Located.Refused -> refuse(id, l.reason)
            is Located.Found -> {
                val file = (l.source as DevSource.Archive).file
                val staged = try {
                    val stamp = nextStamp()
                    installer.stageArchive(adjust = { dir -> retag(dir, stamp) }) { file.inputStream() }
                } finally {
                    withContext(io) { file.delete() }
                }
                finish(l.source.id, staged)
            }
        }
    }

    /**
     * The `--local` path: one request file in the project's `.easyide/dev/`, deleted once read
     * (handled), whatever the outcome.
     */
    suspend fun fromRequest(request: File, projectRoot: File): DevOutcome = lock.withLock {
        val text = withContext(io) {
            try {
                if (!request.isFile || request.length() > MAX_REQUEST_BYTES) null else request.readText()
            } catch (e: IOException) {
                null
            } finally {
                request.delete()
            }
        } ?: return@withLock DevOutcome.Refused("${request.name} could not be read")
        if (!developerMode()) return@withLock refuse(null, "dev request ignored: developer mode (extensions.developerMode) is off")
        when (val l = withContext(io) { DevReload.locateRequest(text, request.name, projectRoot) }) {
            is Located.Refused -> refuse(null, l.reason)
            is Located.Found -> stageFolder((l.source as DevSource.Folder).id, l.source.dir)
        }
    }

    /**
     * A folder the app itself knows (a template "Create extension" just wrote): the same
     * developer install, for the id its manifest declares.
     */
    suspend fun fromFolder(dir: File): DevOutcome = lock.withLock {
        if (!developerMode()) return@withLock refuse(null, "developer install refused: developer mode (extensions.developerMode) is off")
        stageFolder(null, dir)
    }

    /** [id] null: whatever the manifest declares. */
    private suspend fun stageFolder(id: String?, dir: File): DevOutcome {
        val stamp = nextStamp()
        val staged = try {
            installer.stageFolder(withContext(io) { FileFolder.of(dir) }) { d -> retag(d, stamp) }
        } catch (e: IOException) {
            StageResult.Rejected(listOf(e.message ?: e.javaClass.simpleName))
        }
        return finish(id ?: (staged as? StageResult.Staged)?.pkg?.descriptor?.id?.value, staged)
    }

    /** The user approved the sheet: commit as a developer install. @throws IOException as [LocalInstaller.commit]. */
    suspend fun approve(p: DevPending, envId: String?) {
        pendingState.compareAndSet(p, null)
        installer.commit(p.pkg, envId, Source.DEV)
        installedLine(p.pkg)
    }

    /** The sheet was declined or dismissed: nothing installs. */
    suspend fun decline(pkg: StagedPackage) {
        val p = pendingState.value?.takeIf { it.pkg == pkg } ?: return
        if (pendingState.compareAndSet(p, null)) installer.discard(pkg)
        log(LogEntry(pkg.descriptor.id, LogLevel.INFO, "developer install of ${pkg.descriptor.version} declined"))
    }

    private suspend fun finish(requested: String?, staged: StageResult): DevOutcome {
        val pkg = when (staged) {
            is StageResult.Rejected -> return refuse(requested, "dev reload refused: " + staged.problems.joinToString("; "))
            is StageResult.Staged -> staged.pkg
        }
        val id = requested ?: pkg.descriptor.id.value
        return when (val decision = DevReload.decide(id, pkg.descriptor, installed(), currentEnv)) {
            is DevDecision.Refuse -> { installer.discard(pkg); refuse(id, decision.reason) }
            is DevDecision.Silent -> try {
                installer.commit(pkg, decision.envId, Source.DEV)
                installedLine(pkg)
                DevOutcome.Installed(id, pkg.descriptor.version.toString())
            } catch (e: IOException) {
                installer.discard(pkg)
                refuse(id, "dev reload failed: ${e.message ?: e.javaClass.simpleName}")
            }
            is DevDecision.Prompt -> {
                val p = DevPending(pkg, decision.reason, decision.envId, decision.added)
                pendingState.swap(p)?.let { installer.discard(it.pkg) }
                val why = when (decision.reason) {
                    PromptReason.FIRST_INSTALL -> "first developer install"
                    PromptReason.REPLACES_NON_DEV -> "replaces an installed non-developer copy"
                    PromptReason.CAPABILITIES_CHANGED -> "capabilities changed"
                    PromptReason.NEEDS_ENVIRONMENT -> "choose an environment"
                }
                log(LogEntry(pkg.descriptor.id, LogLevel.INFO, "${pkg.descriptor.version} waits for approval in Extensions ($why)"))
                notify("$id: approve in Extensions ($why)")
                DevOutcome.Pending(p)
            }
        }
    }

    private fun installedLine(pkg: StagedPackage) {
        log(LogEntry(pkg.descriptor.id, LogLevel.INFO, "developer install ${pkg.descriptor.version} loaded"))
        notify("${pkg.descriptor.id} reloaded")
    }

    private fun refuse(id: String?, reason: String): DevOutcome.Refused {
        log(LogEntry(id?.let(ExtensionId::parse), LogLevel.WARN, reason))
        notify(reason)
        return DevOutcome.Refused(reason)
    }

    /** Strictly increasing, so two reloads in one millisecond still get distinct versions. */
    private fun nextStamp(): Long {
        lastStamp = maxOf(clock(), lastStamp + 1)
        return lastStamp
    }

    /** Gives the staged manifest its per-reload version; a manifest that is not valid JSON is left for validation to report. */
    private fun retag(dir: File, stamp: Long) {
        val manifest = File(dir, MANIFEST_FILE)
        if (!manifest.isFile) return
        DevReload.withDevVersion(manifest.readText(), stamp)?.let(manifest::writeText)
    }

    private fun <T> MutableStateFlow<T>.swap(value: T): T {
        while (true) {
            val prev = this.value
            if (compareAndSet(prev, value)) return prev
        }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024L
    }
}
