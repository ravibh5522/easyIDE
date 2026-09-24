package dev.easyide.app.ui.screens.extensions

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.data.settings.WorkbenchSettingsSchema
import dev.easyide.app.extensions.adapters.ContributionLocations
import dev.easyide.app.extensions.adapters.UserKeyRows
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.app.extensions.install.FolderNode
import dev.easyide.app.extensions.install.RollbackResult
import dev.easyide.app.extensions.install.StageResult
import dev.easyide.app.extensions.install.StagedPackage
import dev.easyide.extensions.contrib.ContributionConflict
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.host.LoadedExtension
import dev.easyide.extensions.host.PackageProblem
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SafeModeState
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.model.SandboxEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * One contribution as the inspector (ECO-32) lists it, with what the user may do to it:
 * hide/show ([hideable] false for `NON_HIDEABLE` refs) and, where it has an order
 * [location], move up/down.
 */
data class InspectorLine(
    val ref: String,
    val pointer: String,
    val hiddenBy: String?,
    val conflicts: List<ContributionConflict>,
    val hideable: Boolean = true,
    val location: String? = null,
    /** The id inside [location] (a menu entry's command, a row or status item id). */
    val locationId: String = "",
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
) {
    val hidden: Boolean get() = hiddenBy != null
}

/** One row of the Extensions screen: a valid package with its state, or an invalid one with its errors. */
data class ExtensionRow(
    val pkg: InstalledPackage,
    val loaded: LoadedExtension?,
    val problem: PackageProblem?,
    val activation: ActivationState?,
    val disabledReason: DisabledReason?,
    /** The user's toggle: not listed in `extensions.disabled`. */
    val userEnabled: Boolean,
    val contributions: List<InspectorLine>,
    /** Conflicts this extension lost (its entries were dropped, so they are not in [contributions]). */
    val shadowed: List<ContributionConflict>,
    /** The retained version "Roll back" flips to; null when none is kept. */
    val rollbackTo: String? = null,
) {
    val key: String get() = pkg.directory.absolutePath
    val id: String get() = loaded?.descriptor?.id?.value ?: pkg.directory.parentFile?.name.orEmpty()
}

data class ExtensionsUiState(
    val rows: List<ExtensionRow> = emptyList(),
    val safeMode: SafeModeReason? = null,
    val safeModeSuspects: List<ExtensionId> = emptyList(),
    val log: List<TimedLogEntry> = emptyList(),
    val environments: List<SandboxEnvironment> = emptyList(),
)

/** A staged local package waiting on the capability sheet, or the reasons it was refused. */
sealed interface InstallState {
    data object Idle : InstallState
    data object Staging : InstallState
    data class Review(val pkg: StagedPackage) : InstallState
    data class Refused(val problems: List<String>) : InstallState
    data class Failed(val message: String) : InstallState
}

/** A rollback (registry-and-install.md sec 10) from confirmation to its outcome. */
sealed interface RollbackState {
    data object Idle : RollbackState
    data class Confirm(val row: ExtensionRow, val version: String) : RollbackState
    /** The retained version declares [capabilities] never approved for it. */
    data class Approve(val row: ExtensionRow, val descriptor: ExtensionDescriptor, val capabilities: Set<String>) : RollbackState
    data class Refused(val problems: List<String>) : RollbackState
}

/**
 * The Extensions screen: installed and built-in packs with their state, the enable toggle
 * (`extensions.disabled`, whole list per layer), crash-disable clearing, safe mode exit,
 * the contribution inspector, capabilities, the Extension Log, "Install from
 * folder / file" (ECO-02) and rollback through [dev.easyide.app.extensions.install.LocalInstaller].
 */
class ExtensionsViewModel(
    private val appContext: Context,
    private val extensions: ExtensionsContainer,
    private val settingsStore: SettingsStore,
    safeMode: SafeModeState,
    environmentManager: EnvironmentManager,
) : ViewModel() {

    private val runtime = extensions.runtime
    private val installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = installState.asStateFlow()
    private val rollbackState = MutableStateFlow<RollbackState>(RollbackState.Idle)
    val rollback: StateFlow<RollbackState> = rollbackState.asStateFlow()

    private val hosts = combine(runtime.extensions.loaded, runtime.extensions.problems, runtime.extensions.disabledReasons, runtime.activation.states) { l, p, r, a ->
        HostView(l, p, r, a)
    }

    val uiState: StateFlow<ExtensionsUiState> = combine(
        hosts,
        runtime.contributions.snapshot,
        settingsStore.snapshot,
        combine(safeMode.active, runtime.safeMode.suspects) { a, s -> a to s },
        combine(extensions.log.entries, environmentManager.environments, extensions.inventory.retained) { l, e, r -> Triple(l, e, r) },
    ) { h, snapshot, settings, (safe, suspects), (log, envs, retained) ->
        ExtensionsUiState(rows(h, snapshot, settings, retained), safe, suspects, log.asReversed(), envs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ExtensionsUiState())

    private class HostView(
        val loaded: List<LoadedExtension>,
        val problems: List<PackageProblem>,
        val reasons: Map<ExtensionId, DisabledReason>,
        val states: Map<ExtensionId, ActivationState>,
    )

    private fun rows(h: HostView, snapshot: ContributionSnapshot, settings: SettingsSnapshot, retained: Map<String, String>): List<ExtensionRow> {
        val disabled = settings[SettingsSchema.extensionsDisabled].toSet()
        val valid = h.loaded.map { l ->
            val id = l.descriptor.id
            val owner = Owner.Ext(id)
            ExtensionRow(
                pkg = l.pkg, loaded = l, problem = null,
                activation = h.states[id], disabledReason = h.reasons[id],
                userEnabled = id.value !in disabled,
                contributions = inspect(snapshot, owner, settings),
                shadowed = snapshot.conflicts.filter { it.loser == owner },
                rollbackTo = retained[l.pkg.directory.absolutePath],
            )
        }
        val invalid = h.problems.map { p ->
            ExtensionRow(
                p.pkg, null, p, null, null, userEnabled = true, contributions = emptyList(), shadowed = emptyList(),
                rollbackTo = retained[p.pkg.directory.absolutePath],
            )
        }
        return (valid + invalid).sortedWith(compareBy({ it.pkg.installedAt }, { it.id }))
    }

    private fun inspect(s: ContributionSnapshot, owner: Owner, settings: SettingsSnapshot): List<InspectorLine> {
        val hidden = settings[SettingsSchema.contributionsHidden]
        val overrides = ContributionOverrides.of(hidden, ContributionOverrides.parseOrder(settings[WorkbenchSettingsSchema.contributionsOrder]))
        val userRows = UserKeyRows.decode(settings[WorkbenchSettingsSchema.keyRowLayouts]).rows
        val effective = HashMap<String, List<String>>()
        val all: List<Owned<*>> = s.commands + s.menus + s.keybindings + s.configuration + s.configurationDefaults +
            s.languages + s.grammars + s.languageConfigurations + s.snippets + s.themes + s.iconThemes + s.viewContainers +
            s.views + s.viewsWelcome + s.taskDefinitions + s.problemMatchers + s.walkthroughs + s.stages + s.statusBarItems +
            s.keyRows + s.languageServers + s.sandbox + s.viewData
        return all.filter { it.owner == owner }.map { o ->
            val entry = runtime.contributions.inspect(o.ref, overrides.hidden)
            val ref = o.ref.toString()
            val location = ContributionLocations.of(o)?.takeIf { !overrides.isHidden(o.ref) }
            val ids = location?.let { effective.getOrPut(it) { ContributionLocations.effectiveIds(it, s, userRows, overrides.hidden, overrides.order) } }
            val at = ids?.indexOf(o.ref.id) ?: -1
            InspectorLine(
                ref, o.pointer, entry?.hiddenBy, entry?.conflicts.orEmpty(),
                hideable = ContributionOverrides.isHideable(ref),
                location = location, locationId = o.ref.id,
                canMoveUp = at > 0, canMoveDown = ids != null && at >= 0 && at < ids.size - 1,
            )
        }
    }

    /**
     * Hide or show one contribution: the whole effective `workbench.contributions.hidden`
     * list goes to the user layer (customization.md 3.3). Non-hideable refs are refused.
     */
    fun setHidden(line: InspectorLine, hide: Boolean) {
        viewModelScope.launch {
            val current = settingsStore.snapshot.first()[SettingsSchema.contributionsHidden]
            val next = ContributionOverrides.withHidden(current, line.ref, hide) ?: return@launch
            settingsStore.set(SettingsSchema.contributionsHidden, next)
        }
    }

    /** Moves one contribution within its location, writing `workbench.contributions.order` to the user layer. */
    fun move(line: InspectorLine, delta: Int) {
        val location = line.location ?: return
        viewModelScope.launch {
            val settings = settingsStore.snapshot.first()
            val overrides = ContributionOverrides.of(
                settings[SettingsSchema.contributionsHidden], ContributionOverrides.parseOrder(settings[WorkbenchSettingsSchema.contributionsOrder]),
            )
            val userRows = UserKeyRows.decode(settings[WorkbenchSettingsSchema.keyRowLayouts]).rows
            val effective = ContributionLocations.effectiveIds(location, runtime.contributions.snapshot.value, userRows, overrides.hidden, overrides.order)
            val next = ContributionLocations.moved(overrides.order, location, effective, line.locationId, delta) ?: return@launch
            settingsStore.set(WorkbenchSettingsSchema.contributionsOrder, ContributionOverrides.encodeOrder(next))
        }
    }

    fun setEnabled(row: ExtensionRow, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && row.disabledReason == DisabledReason.CRASH_DISABLED) {
                row.loaded?.descriptor?.id?.let { runtime.activation.clearCrashDisable(it) }
            }
            // The whole stored list, including ids of packs not installed right now.
            val current = settingsStore.get(SettingsSchema.extensionsDisabled).first().toSet()
            val next = if (enabled) current - row.id else current + row.id
            settingsStore.set(SettingsSchema.extensionsDisabled, next.sorted())
        }
    }

    /** Session reasons end for this process; the setting is cleared when it is set. */
    fun exitSafeMode() {
        viewModelScope.launch { extensions.exitSafeMode() }
    }

    fun uninstall(row: ExtensionRow) {
        viewModelScope.launch { extensions.installer.uninstall(row.pkg) }
    }

    fun requestRollback(row: ExtensionRow) {
        row.rollbackTo?.let { rollbackState.value = RollbackState.Confirm(row, it) }
    }

    /** Rolls [row] back; [approve] is the capability set the user just approved, if any. */
    fun confirmRollback(row: ExtensionRow, approve: Set<String> = emptySet()) {
        rollbackState.value = RollbackState.Idle
        viewModelScope.launch {
            rollbackState.value = when (val r = extensions.installer.rollback(row.id, row.pkg.scope, row.pkg.envId, approve)) {
                is RollbackResult.Done -> RollbackState.Idle
                is RollbackResult.NeedsApproval -> RollbackState.Approve(row, r.descriptor, r.capabilities)
                is RollbackResult.Refused -> RollbackState.Refused(r.problems)
            }
        }
    }

    fun dismissRollback() { rollbackState.value = RollbackState.Idle }

    fun clearLog() = extensions.log.clear()

    /** A picked `.easyext` file (SAF document). */
    fun stageArchive(uri: Uri) = stage {
        extensions.installer.stageArchive {
            appContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        }
    }

    /** A picked folder (SAF tree) holding an unpacked package. */
    fun stageFolder(uri: Uri) = stage {
        val root = DocumentFile.fromTreeUri(appContext, uri) ?: throw FileNotFoundException(uri.toString())
        extensions.installer.stageFolder(DocumentFolder(root, appContext))
    }

    fun approve(pkg: StagedPackage, envId: String?) {
        viewModelScope.launch {
            installState.value = try {
                extensions.installer.commit(pkg, envId)
                InstallState.Idle
            } catch (e: IOException) {
                extensions.installer.discard(pkg)
                InstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun decline(pkg: StagedPackage) {
        viewModelScope.launch {
            extensions.installer.discard(pkg)
            installState.value = InstallState.Idle
        }
    }

    fun dismissInstall() { installState.value = InstallState.Idle }

    private fun stage(block: suspend () -> StageResult) {
        installState.value = InstallState.Staging
        viewModelScope.launch {
            // SAF boundary: a revoked or vanished document surfaces here.
            installState.value = try {
                when (val r = block()) {
                    is StageResult.Staged -> InstallState.Review(r.pkg)
                    is StageResult.Rejected -> InstallState.Refused(r.problems)
                }
            } catch (e: IOException) {
                InstallState.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: SecurityException) {
                InstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private companion object { const val SUBSCRIPTION_TIMEOUT_MS = 5_000L }
}

/** SAF tree as a [FolderNode]; SAF has no symlinks, and names are validated by the unpacker. */
private class DocumentFolder(private val doc: DocumentFile, private val context: Context) : FolderNode {
    override val name: String get() = doc.name.orEmpty()
    override val isDirectory: Boolean get() = doc.isDirectory
    override fun children(): List<FolderNode> = doc.listFiles().map { DocumentFolder(it, context) }
    override fun open(): InputStream = context.contentResolver.openInputStream(doc.uri) ?: throw FileNotFoundException(doc.uri.toString())
}
