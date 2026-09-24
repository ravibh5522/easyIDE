package dev.easyide.extwasm

import dev.easyide.extwasm.host.WasmExtension
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lifecycle of one extension's instance (wasm-host.md sec 5). DISCARDED is not a resting
 * state: after a trap the host goes straight to [Unloaded] (next command re-instantiates)
 * or [Disabled] (crash window full).
 */
sealed interface InstanceState {
    data object Unloaded : InstanceState
    data object Loading : InstanceState
    data object Activating : InstanceState
    data object Active : InstanceState
    data object Busy : InstanceState
    data object Deactivating : InstanceState

    /** Load or activation failed; [reason] is what the Extension Log shows. */
    data class Failed(val reason: String) : InstanceState

    /** Crash loop: disabled until the user re-enables the extension. */
    data class Disabled(val reason: String) : InstanceState
}

/** Environment facts sent in the activation message. */
data class EnvInfo(val id: String, val distro: String, val arch: String)

/** Everything besides the extension itself that `ext_activate` carries. */
data class ActivationContext(
    val apiVersion: String,
    /** Resolved values of the extension's own settings. */
    val settings: JsonObject,
    val env: EnvInfo,
) {
    internal fun message(ext: WasmExtension): JsonObject = buildJsonObject {
        put("v", WasmPolicy.ABI_VERSION)
        put("type", "activate")
        put("extensionId", ext.id)
        put("version", ext.version)
        put("apiVersion", apiVersion)
        put("capabilities", JsonArray(ext.capabilities.ids.map(::JsonPrimitive)))
        put("settings", settings)
        put("env", buildJsonObject { put("id", env.id); put("distro", env.distro); put("arch", env.arch) })
    }
}

/**
 * Callback into the extension state store (`ExtensionStateStore.disable(id, CRASH_LOOP)` in
 * `:extensions`), invoked once when the crash window fills.
 */
fun interface CrashPolicy {
    fun disableForCrashLoop(extensionId: String, reason: String)
}
