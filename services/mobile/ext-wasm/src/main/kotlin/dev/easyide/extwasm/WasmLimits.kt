package dev.easyide.extwasm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Read side of the settings store as `:ext-wasm` needs it: the effective value of a G-scope
 * key, or null when unset (the default below then applies). Implemented in `:app` over
 * `SettingsStore` + `SettingsResolver` (hld.md `SettingsQuery`).
 */
fun interface SettingsLookup {
    fun value(key: String): JsonElement?
}

/** `extensions.wasm.*` keys and their defaults, verbatim from sdk-reference "Settings keys". */
enum class WasmSetting(val key: String, val default: Long) {
    MAX_MODULE_MB("extensions.wasm.maxModuleMb", 8),
    MAX_MEMORY_MB("extensions.wasm.maxMemoryMb", 64),
    FUEL_PER_CALL("extensions.wasm.fuelPerCall", 50_000_000),
    MAX_HOST_CALLS_PER_CALL("extensions.wasm.maxHostCallsPerCall", 1000),
    CALL_TIMEOUT_MS("extensions.wasm.callTimeoutMs", 2000),
    ACTIVATE_TIMEOUT_MS("extensions.wasm.activateTimeoutMs", 5000),
    DEACTIVATE_TIMEOUT_MS("extensions.wasm.deactivateTimeoutMs", 2000),
    MAX_MESSAGE_KB("extensions.wasm.maxMessageKb", 4096),
    NET_MAX_RESPONSE_KB("extensions.wasm.netMaxResponseKb", 4096),
    MAX_CRASHES("extensions.wasm.maxCrashes", 3),
    CRASH_WINDOW_SEC("extensions.wasm.crashWindowSec", 300),
    STORAGE_QUOTA_KB("extensions.storage.quotaKb", 5120),
}

/** Master switch `extensions.wasm.enabled` (default true). */
const val WASM_ENABLED_KEY = "extensions.wasm.enabled"

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val WASM_PAGE_BYTES = 64L * 1024L
private const val MS_PER_SEC = 1000L

/** The wasm32 address space holds at most 65536 pages. */
private const val MAX_WASM_PAGES = 65536L

private fun Long.toIntClamped(max: Long = Int.MAX_VALUE.toLong()): Int = minOf(this, max).toInt()

/**
 * All limits of one instance, resolved once at instantiation (a settings change applies to
 * the next instance). Sizes are already in bytes/pages so enforcement points do no unit math.
 */
data class WasmLimits(
    val maxModuleBytes: Long,
    val maxMemoryPages: Int,
    val fuelPerCall: Long,
    val maxHostCallsPerCall: Int,
    val callTimeoutMs: Long,
    val activateTimeoutMs: Long,
    val deactivateTimeoutMs: Long,
    val maxMessageBytes: Int,
    val netMaxResponseBytes: Int,
    val storageQuotaBytes: Long,
    val maxCrashes: Int,
    val crashWindowMs: Long,
) {
    companion object {
        /**
         * Resolves every key; a value that is not an integer in 1..Int.MAX_VALUE is ignored in
         * favour of the default, because a zero timeout or fuel would make every extension
         * unusable rather than stricter, and the upper bound keeps unit conversion in range. [manifestMemoryMb] (`easyide.wasm.memoryMb`) can only lower the cap.
         */
        fun resolve(settings: SettingsLookup, manifestMemoryMb: Int? = null): WasmLimits {
            fun get(s: WasmSetting): Long =
                ((settings.value(s.key) as? JsonPrimitive)?.longOrNull)?.takeIf { it in 1..Int.MAX_VALUE } ?: s.default
            val capMb = get(WasmSetting.MAX_MEMORY_MB)
            val memoryMb = minOf(manifestMemoryMb?.toLong()?.takeIf { it > 0 } ?: capMb, capMb)
            return WasmLimits(
                maxModuleBytes = get(WasmSetting.MAX_MODULE_MB) * BYTES_PER_MB,
                maxMemoryPages = (memoryMb * BYTES_PER_MB / WASM_PAGE_BYTES).toIntClamped(MAX_WASM_PAGES),
                fuelPerCall = get(WasmSetting.FUEL_PER_CALL),
                maxHostCallsPerCall = get(WasmSetting.MAX_HOST_CALLS_PER_CALL).toIntClamped(),
                callTimeoutMs = get(WasmSetting.CALL_TIMEOUT_MS),
                activateTimeoutMs = get(WasmSetting.ACTIVATE_TIMEOUT_MS),
                deactivateTimeoutMs = get(WasmSetting.DEACTIVATE_TIMEOUT_MS),
                maxMessageBytes = (get(WasmSetting.MAX_MESSAGE_KB) * BYTES_PER_KB).toIntClamped(),
                netMaxResponseBytes = (get(WasmSetting.NET_MAX_RESPONSE_KB) * BYTES_PER_KB).toIntClamped(),
                storageQuotaBytes = get(WasmSetting.STORAGE_QUOTA_KB) * BYTES_PER_KB,
                maxCrashes = get(WasmSetting.MAX_CRASHES).toIntClamped(),
                crashWindowMs = get(WasmSetting.CRASH_WINDOW_SEC) * MS_PER_SEC,
            )
        }

        fun enabled(settings: SettingsLookup): Boolean =
            (settings.value(WASM_ENABLED_KEY) as? JsonPrimitive)?.booleanOrNull ?: true
    }
}
