package dev.easyide.extwasm.load

import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import dev.easyide.extwasm.ModuleRejectedException
import dev.easyide.extwasm.WasmPolicy

/** ABI v1 symbol names and signatures (sdk-reference "WASM host API"). */
object AbiV1 {
    const val HOST_MODULE = "easyide"
    const val HOST_CALL = "host_call"
    const val MEMORY = "memory"
    const val ALLOC = "alloc"
    const val FREE = "free"
    const val ABI_VERSION = "ext_abi_version"
    const val ACTIVATE = "ext_activate"
    const val HANDLE = "ext_handle"

    val HOST_CALL_TYPE: FunctionType = FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))

    /** Required function exports and their exact types. */
    val FUNCTION_EXPORTS: Map<String, FunctionType> = mapOf(
        ALLOC to FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
        FREE to FunctionType.of(listOf(ValType.I32, ValType.I32), listOf()),
        ABI_VERSION to FunctionType.of(listOf(), listOf(ValType.I32)),
        ACTIVATE to HOST_CALL_TYPE,
        HANDLE to HOST_CALL_TYPE,
    )
}

/**
 * Static checks of wasm-host.md sec 3.2, run on the parsed module before any guest code
 * can execute. Each failure names the rule so `easyide-ext validate` and the Extension Log
 * can say exactly what to fix.
 */
object ModuleValidator {

    /** @return the memory limits to instantiate with (the module's max clamped to the cap). */
    fun validate(module: WasmModule, maxMemoryPages: Int): MemoryLimits {
        checkImports(module)
        checkExports(module)
        if (module.startSection().isPresent) {
            reject("a start function is not allowed (it would run before limits are armed)")
        }
        return memoryLimits(module, maxMemoryPages)
    }

    private fun checkImports(module: WasmModule) {
        val imports = module.importSection()
        for (i in 0 until imports.importCount()) {
            val imp = imports.getImport(i)
            val isHostCall = imp is FunctionImport && imp.module() == AbiV1.HOST_MODULE && imp.name() == AbiV1.HOST_CALL
            if (!isHostCall) {
                reject("import ${imp.module()}.${imp.name()} is not allowed: only ${AbiV1.HOST_MODULE}.${AbiV1.HOST_CALL} may be imported")
            }
            if (module.typeSection().getType(imp.typeIndex()) != AbiV1.HOST_CALL_TYPE) {
                reject("${AbiV1.HOST_MODULE}.${AbiV1.HOST_CALL} must have type (i32, i32) -> i32")
            }
        }
        if (imports.importCount() > 1) reject("${AbiV1.HOST_MODULE}.${AbiV1.HOST_CALL} is imported more than once")
    }

    private fun checkExports(module: WasmModule) {
        val exports = module.exportSection()
        val importedFunctions = module.importSection().count(ExternalType.FUNCTION)
        val found = HashMap<String, FunctionType>()
        var memoryExported = false
        for (i in 0 until exports.exportCount()) {
            val e = exports.getExport(i)
            if (e.name().startsWith(WasmPolicy.RESERVED_NAME_PREFIX)) {
                reject("export name ${e.name()} uses the reserved prefix ${WasmPolicy.RESERVED_NAME_PREFIX}")
            }
            when (e.exportType()) {
                ExternalType.FUNCTION -> {
                    val idx = e.index()
                    val typeIdx = if (idx < importedFunctions) {
                        (module.importSection().getImport(idx) as FunctionImport).typeIndex()
                    } else {
                        module.functionSection().getFunctionType(idx - importedFunctions)
                    }
                    found[e.name()] = module.typeSection().getType(typeIdx)
                }
                ExternalType.MEMORY -> if (e.name() == AbiV1.MEMORY) memoryExported = true
                else -> Unit
            }
        }
        if (!memoryExported) reject("missing export: memory")
        for ((name, type) in AbiV1.FUNCTION_EXPORTS) {
            val actual = found[name] ?: reject("missing export: $name")
            if (actual != type) reject("export $name has type ${sig(actual)}, expected ${sig(type)}")
        }
    }

    private fun memoryLimits(module: WasmModule, capPages: Int): MemoryLimits {
        val section = module.memorySection().orElse(null)
        if (section == null || section.memoryCount() != 1) reject("exactly one memory is required")
        val limits = section.getMemory(0).limits()
        if (limits.shared()) reject("shared memory (threads) is not supported")
        if (limits.initialPages() > capPages) {
            reject("memory needs ${limits.initialPages()} initial pages, the cap is $capPages (extensions.wasm.maxMemoryMb)")
        }
        return MemoryLimits(limits.initialPages(), minOf(limits.maximumPages(), capPages))
    }

    private fun sig(t: FunctionType): String =
        t.params().joinToString(", ", "(", ")") { it.toString() } + " -> " + t.returns().joinToString(", ", "(", ")") { it.toString() }

    private fun reject(message: String): Nothing = throw ModuleRejectedException(message)
}
