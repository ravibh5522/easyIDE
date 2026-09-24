package dev.easyide.extensions.manifest

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.InputSpec
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.PackageFile

enum class Layer { L0, L1, L2 }

enum class InstallScope(val wire: String) {
    GLOBAL("global"), ENVIRONMENT("environment");

    companion object {
        fun parse(wire: String): InstallScope? = entries.firstOrNull { it.wire == wire }
    }
}

enum class Source { BUILT_IN, REGISTRY, OPEN_VSX, SIDELOAD, DEV }

/** sdk-reference `activationEvents`; `*` is read as [OnStartupFinished] with a warning. */
sealed interface ActivationEvent {
    data class OnLanguage(val languageId: String) : ActivationEvent
    data class OnCommand(val commandId: String) : ActivationEvent
    data class OnView(val viewId: String) : ActivationEvent
    data class OnStage(val stageId: String) : ActivationEvent
    data class WorkspaceContains(val glob: String) : ActivationEvent
    data object OnStartupFinished : ActivationEvent

    companion object {
        /** Null for an unknown or empty-argument event. */
        fun parse(raw: String): ActivationEvent? {
            if (raw == "onStartupFinished" || raw == "*") return OnStartupFinished
            val colon = raw.indexOf(':')
            if (colon <= 0 || colon == raw.length - 1) return null
            val arg = raw.substring(colon + 1)
            return when (raw.substring(0, colon)) {
                "onLanguage" -> OnLanguage(arg)
                "onCommand" -> OnCommand(arg)
                "onView" -> OnView(arg)
                "onStage" -> OnStage(arg)
                "workspaceContains" -> WorkspaceContains(arg)
                else -> null
            }
        }
    }
}

data class WasmProvider(val kind: String, val languages: List<String>)

data class WasmSpec(val module: PackageFile, val abi: Int, val memoryMb: Int?, val providers: List<WasmProvider>)

/**
 * A validated extension: everything the runtime needs, with every `when` parsed, every
 * string parameter a Template and every file reference resolved. Built only by
 * [ManifestParser], so holding one means the manifest passed every load-time check.
 */
data class ExtensionDescriptor(
    val id: ExtensionId,
    val version: SemVer,
    val displayName: String,
    val description: String?,
    val license: String?,
    val engines: SemVerRange,
    val categories: List<String>,
    val scope: InstallScope,
    val layers: Set<Layer>,
    /** Declared capabilities; granted = declared AND approved (see ExtensionHost). */
    val capabilities: CapabilitySet,
    val activationEvents: List<ActivationEvent>,
    val contributes: Contributions,
    val actions: Map<String, Action>,
    val inputs: Map<String, InputSpec>,
    val wasm: WasmSpec?,
    val memoryBudgetMb: Int?,
    val icon: PackageFile?,
    /** Host directory of the unpacked package; never shown to guests. */
    val root: String,
) {
    /** `${extensionPath}`: where the active version is visible inside the environment. */
    val guestRoot: String get() = GUEST_EXTENSIONS_ROOT + id.value

    companion object {
        const val GUEST_EXTENSIONS_ROOT = "/opt/easyide/extensions/"
    }
}
