package dev.easyide.extensions.host

import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.bool
import dev.easyide.extensions.settings.ExtensionSettings.strings
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * One installed version on disk. Install, `current` flips, approvals and system disables
 * live in `state.json`, owned by the installer (registry-and-install.md); the runtime only
 * reads them and reports crash-disables back.
 */
data class InstalledPackage(
    val directory: File,
    val scope: InstallScope,
    /** Environment of an environment-scoped install; null for global installs. */
    val envId: String?,
    val source: Source,
    /** Tie-break for every conflict rule (earliest first). */
    val installedAt: Long,
    /** Capability ids the user approved for this version. Built-ins need none. */
    val approvedCapabilities: Set<String>,
    val revoked: Boolean,
    val crashDisabled: Boolean,
)

interface ExtensionInventory {
    val installed: StateFlow<List<InstalledPackage>>

    /** Persists a crash-disable (or its clearing, when the user re-enables). */
    suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean)
}

/** Why an installed extension is not enabled for a scope; null from [Enablement.check] means enabled. */
enum class DisabledReason {
    OTHER_ENVIRONMENT, EXTENSIONS_OFF, SAFE_MODE, USER_DISABLED, NOT_IN_PROFILE, NEEDS_APPROVAL, CRASH_DISABLED, REVOKED,
}

/** Settings-derived inputs of the enablement rules, resolved once per recomputation. */
data class EnablementInputs(
    val runtime: RuntimeScope,
    val extensionsEnabled: Boolean,
    val safeMode: Boolean,
    /** `extensions.disabled` for the runtime scope (P scope; arrays replace, customization.md 3.3). */
    val disabled: Set<ExtensionId>,
    /** Active profile's `enabledExtensions`, or null when the profile does not restrict. */
    val profileAllowlist: Set<ExtensionId>?,
) {
    companion object {
        fun read(settings: SettingsPort, runtime: RuntimeScope, safeMode: Boolean): EnablementInputs {
            val query = SettingsQuery.of(runtime)
            return EnablementInputs(
                runtime = runtime,
                extensionsEnabled = settings.bool(ExtensionSettings.ENABLED),
                safeMode = safeMode,
                disabled = settings.strings(ExtensionSettings.DISABLED, query).mapNotNullTo(HashSet(), ExtensionId::parse),
                profileAllowlist = settings.profileExtensions()?.mapNotNullTo(HashSet(), ExtensionId::parse),
            )
        }
    }
}

/** The six enablement rules of extension-runtime.md sec 4, as one pure function. */
object Enablement {

    fun check(pkg: InstalledPackage, d: ExtensionDescriptor, inputs: EnablementInputs): DisabledReason? {
        val builtIn = pkg.source == Source.BUILT_IN
        return when {
            pkg.scope == InstallScope.ENVIRONMENT && pkg.envId != inputs.runtime.envId -> DisabledReason.OTHER_ENVIRONMENT
            pkg.revoked -> DisabledReason.REVOKED
            !builtIn && !inputs.extensionsEnabled -> DisabledReason.EXTENSIONS_OFF
            !builtIn && inputs.safeMode -> DisabledReason.SAFE_MODE
            d.id in inputs.disabled -> DisabledReason.USER_DISABLED
            pkg.scope == InstallScope.GLOBAL && inputs.profileAllowlist != null && d.id !in inputs.profileAllowlist -> DisabledReason.NOT_IN_PROFILE
            !builtIn && !approved(pkg).let { a -> d.capabilities.items.all(a::satisfies) } -> DisabledReason.NEEDS_APPROVAL
            pkg.crashDisabled -> DisabledReason.CRASH_DISABLED
            else -> null
        }
    }

    /**
     * Declared AND approved. For an enabled extension this equals the declared set (rule 5
     * requires the approval to cover it); built-ins are trusted with what they declare.
     */
    fun granted(pkg: InstalledPackage, d: ExtensionDescriptor): CapabilitySet =
        if (pkg.source == Source.BUILT_IN) d.capabilities else d.capabilities.intersect(approved(pkg))

    private fun approved(pkg: InstalledPackage): CapabilitySet =
        CapabilitySet(pkg.approvedCapabilities.mapNotNullTo(HashSet(), Capability::parse))
}
