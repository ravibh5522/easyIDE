package dev.easyide.extensions.settings

import dev.easyide.extensions.json.booleanOrNull
import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray

/**
 * The sdk-reference settings keys this module reads, with their documented defaults: the
 * single place those names and numbers appear (R-ENG-07). A stored value of the wrong type
 * or below [IntKey.min] falls back to the default, like every other invalid setting.
 */
object ExtensionSettings {

    class IntKey internal constructor(val key: String, val default: Int, val min: Int = 1)
    class BoolKey internal constructor(val key: String, val default: Boolean)

    val ENABLED = BoolKey("extensions.enabled", true)
    val SAFE_MODE = BoolKey("extensions.safeMode", false)
    const val DISABLED = "extensions.disabled"

    val LIMITS_PACKAGE_MB = IntKey("extensions.limits.packageMb", 50)
    val LIMITS_UNPACKED_MB = IntKey("extensions.limits.unpackedMb", 200)
    val LIMITS_FILE_MB = IntKey("extensions.limits.fileMb", 20)
    val WASM_MAX_MODULE_MB = IntKey("extensions.wasm.maxModuleMb", 8)

    val ACTIONS_EXEC_TIMEOUT_SEC = IntKey("extensions.actions.execTimeoutSec", 600)
    val ACTIONS_CAPTURE_KB = IntKey("extensions.actions.captureKb", 1024)

    val WASM_ACTIVATE_TIMEOUT_MS = IntKey("extensions.wasm.activateTimeoutMs", 5000)
    val WASM_DEACTIVATE_TIMEOUT_MS = IntKey("extensions.wasm.deactivateTimeoutMs", 2000)
    val WASM_MAX_CRASHES = IntKey("extensions.wasm.maxCrashes", 3)
    val WASM_CRASH_WINDOW_SEC = IntKey("extensions.wasm.crashWindowSec", 300)

    const val WORKBENCH_HIDDEN = "workbench.contributions.hidden"

    const val MB = 1024L * 1024L
    const val KB = 1024

    fun SettingsPort.int(k: IntKey, q: SettingsQuery = GLOBAL): Int =
        value(k.key, q)?.intOrNull?.takeIf { it >= k.min } ?: k.default

    fun SettingsPort.bool(k: BoolKey, q: SettingsQuery = GLOBAL): Boolean = value(k.key, q)?.booleanOrNull ?: k.default

    /** A string-array setting; non-string entries are dropped, a non-array value means unset. */
    fun SettingsPort.strings(key: String, q: SettingsQuery): List<String> =
        (value(key, q) as? JsonArray)?.mapNotNull { it.stringOrNull } ?: emptyList()

    /** G-scope keys ignore environment and project, so they are read with no coordinates. */
    val GLOBAL = SettingsQuery(null, null, null)
}
