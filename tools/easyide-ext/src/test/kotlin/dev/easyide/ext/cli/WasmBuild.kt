package dev.easyide.ext.cli

import org.junit.Assert.assertEquals
import java.io.File

/** Builds a generated WASM template with its own build.sh; false when the Rust wasm32 target is absent. */
object WasmBuild {
    private val available: Boolean by lazy {
        runCatching {
            ProcessBuilder("rustup", "target", "list", "--installed").start().inputStream.bufferedReader().readText()
                .contains("wasm32-unknown-unknown")
        }.getOrDefault(false)
    }

    fun build(dir: File): Boolean {
        if (!available) return false
        val p = ProcessBuilder("sh", "build.sh").directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return true
    }
}
