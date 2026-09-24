package dev.easyide.extwasm.load

import com.dylibso.chicory.wasm.Parser
import dev.easyide.extwasm.ModuleRejectedException
import dev.easyide.extwasm.WasmLimits
import dev.easyide.extwasm.binary.Meter

/**
 * Install-time static validation of a WASM module without instantiating it: size, the
 * metering pass, Chicory's parser and ABI v1 rules ([ModuleValidator]). The same steps
 * `WasmModuleLoader` runs before every instantiation on device, so `easyide-ext validate`
 * refuses exactly the modules the app would.
 */
object WasmStaticCheck {
    /** Null when [bytes] would load; else the refusal the app would log. */
    fun reject(bytes: ByteArray, limits: WasmLimits): String? = try {
        if (bytes.size > limits.maxModuleBytes) {
            throw ModuleRejectedException("module is ${bytes.size} bytes, the limit is ${limits.maxModuleBytes} (extensions.wasm.maxModuleMb)")
        }
        val metered = Meter.instrument(bytes)
        // Untrusted bytes: any parser failure is a refusal, never a crash.
        val module = try { Parser.parse(metered.bytes) } catch (e: RuntimeException) { throw ModuleRejectedException("invalid wasm module: ${e.message}") }
        ModuleValidator.validate(module, limits.maxMemoryPages)
        null
    } catch (e: ModuleRejectedException) {
        e.message ?: "module rejected"
    }
}
