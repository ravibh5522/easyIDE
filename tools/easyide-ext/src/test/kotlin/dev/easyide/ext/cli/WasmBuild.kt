package dev.easyide.ext.cli

import org.junit.Assert.assertEquals
import java.io.File

/**
 * Builds a generated WASM template with its own build.sh. False (skip) when the template's
 * toolchain is absent: the Rust wasm32 target for wasm-rust, npm for wasm-assemblyscript.
 */
object WasmBuild {
    private fun output(vararg cmd: String): String? =
        runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } } }.getOrNull()

    private val rust: Boolean by lazy { output("rustup", "target", "list", "--installed")?.contains("wasm32-unknown-unknown") == true }
    private val npm: Boolean by lazy { output("npm", "--version") != null }

    fun build(dir: File): Boolean {
        val template = File(dir, "guest/Cargo.toml").exists()
        if (if (template) !rust else !npm) return false
        val p = ProcessBuilder("sh", "build.sh").directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return true
    }
}
