package dev.easyide.extensions.host

import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A package that passed validation, with its load warnings. */
data class LoadedExtension(val pkg: InstalledPackage, val descriptor: ExtensionDescriptor, val warnings: List<Diagnostic>)

/** A package that failed layout or manifest validation; shown on the Extensions screen. */
data class PackageProblem(val pkg: InstalledPackage, val errors: List<Diagnostic>, val warnings: List<Diagnostic>)

/** An enabled extension and what it may use. */
data class EnabledExtension(val descriptor: ExtensionDescriptor, val granted: CapabilitySet, val pkg: InstalledPackage) {
    val id: ExtensionId get() = descriptor.id
}

/** Enabled extensions in tie-break order (installedAt, then id); [version] bumps per change. */
data class EnabledSet(val version: Long, val extensions: List<EnabledExtension>) {
    fun byId(id: ExtensionId): EnabledExtension? = extensions.firstOrNull { it.id == id }
    companion object { val EMPTY = EnabledSet(0, emptyList()) }
}

/**
 * Discover -> validate -> enable (extension-runtime.md sec 2 and 4). Every installed package
 * is read and validated once per directory (version directories are immutable once
 * installed), off the main thread; enablement is recomputed from settings, safe mode and
 * the runtime scope, and [enabled] emits only when the `(id, version)` set changes.
 */
class ExtensionHost(
    private val inventory: ExtensionInventory,
    private val settings: SettingsPort,
    private val safeMode: SafeModeState,
    private val parser: ManifestParser,
    private val limits: () -> PackageLimits,
    private val io: CoroutineDispatcher,
) {
    private val cache = ConcurrentHashMap<String, Validation>()
    private val loadedState = MutableStateFlow<List<LoadedExtension>>(emptyList())
    private val problemState = MutableStateFlow<List<PackageProblem>>(emptyList())
    private val enabledState = MutableStateFlow(EnabledSet.EMPTY)
    private val reasons = MutableStateFlow<Map<ExtensionId, DisabledReason>>(emptyMap())
    @Volatile private var runtime = RuntimeScope.NONE

    val loaded: StateFlow<List<LoadedExtension>> = loadedState.asStateFlow()
    val problems: StateFlow<List<PackageProblem>> = problemState.asStateFlow()
    val enabled: StateFlow<EnabledSet> = enabledState.asStateFlow()

    /** Why each loaded-but-not-enabled extension is off, for the Extensions screen. */
    val disabledReasons: StateFlow<Map<ExtensionId, DisabledReason>> = reasons.asStateFlow()

    private sealed interface Validation {
        data class Ok(val loaded: LoadedExtension) : Validation
        data class Bad(val problem: PackageProblem) : Validation
    }

    /** Validates packages not seen before (on [io]) and recomputes enablement. */
    suspend fun refresh(packages: List<InstalledPackage> = inventory.installed.value) {
        val results = withContext(io) {
            packages.map { pkg ->
                val key = pkg.directory.absolutePath
                val cached = cache[key]
                val fresh = if (cached == null) validate(pkg) else withPackage(cached, pkg)
                cache[key] = fresh
                fresh
            }
        }
        cache.keys.retainAll(packages.mapTo(HashSet()) { it.directory.absolutePath })
        loadedState.value = results.filterIsInstance<Validation.Ok>().map { it.loaded }
        problemState.value = results.filterIsInstance<Validation.Bad>().map { it.problem }
        recompute()
    }

    /** Switches environment/project; env-scoped packs and P-scope settings follow. */
    @Synchronized
    fun setRuntime(scope: RuntimeScope) {
        runtime = scope
        recompute()
    }

    /** Re-evaluates the enablement rules; call on settings or safe-mode changes. */
    @Synchronized
    fun recompute() {
        val inputs = EnablementInputs.read(settings, runtime, safeMode.isActive)
        val off = HashMap<ExtensionId, DisabledReason>()
        val on = ArrayList<EnabledExtension>()
        // Two installs of one id (global + environment): the earliest installed wins.
        val seen = HashSet<ExtensionId>()
        for (l in loadedState.value.sortedWith(compareBy({ it.pkg.installedAt }, { it.descriptor.id.value }))) {
            val reason = Enablement.check(l.pkg, l.descriptor, inputs)
            if (reason != null) { off.putIfAbsent(l.descriptor.id, reason); continue }
            if (seen.add(l.descriptor.id)) on += EnabledExtension(l.descriptor, Enablement.granted(l.pkg, l.descriptor), l.pkg)
        }
        on.forEach { off.remove(it.id) }
        reasons.value = off
        enabledState.update { prev ->
            val same = prev.extensions.map { it.id to it.descriptor.version } == on.map { it.id to it.descriptor.version }
            if (same) prev else EnabledSet(prev.version + 1, on)
        }
    }

    /** I/O boundary: layout and manifest problems become a [PackageProblem], never an exception. */
    private fun validate(pkg: InstalledPackage): Validation = when (val layout = PackageLayoutReader.read(pkg.directory, limits())) {
        is PackageLayout.Invalid -> Validation.Bad(PackageProblem(pkg, layout.errors, emptyList()))
        is PackageLayout.Ok -> when (val r = parser.parse(layout.files)) {
            is ParseResult.Ok -> Validation.Ok(LoadedExtension(pkg, r.descriptor, r.warnings))
            is ParseResult.Invalid -> Validation.Bad(PackageProblem(pkg, r.errors, r.warnings))
        }
    }

    /** Package metadata (approvals, crash flag) changes without the files changing. */
    private fun withPackage(v: Validation, pkg: InstalledPackage): Validation = when (v) {
        is Validation.Ok -> Validation.Ok(v.loaded.copy(pkg = pkg))
        is Validation.Bad -> Validation.Bad(v.problem.copy(pkg = pkg))
    }
}
