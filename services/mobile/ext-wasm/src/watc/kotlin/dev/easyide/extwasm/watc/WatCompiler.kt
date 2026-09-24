package dev.easyide.extwasm.watc

import com.dylibso.chicory.wabt.Wat2Wasm
import java.io.File

private val INCLUDE = Regex(";; #include (\\S+)")

/**
 * Build-time fixture compiler: every `<name>.wat` in args[0] becomes `<name>.wasm` in args[1].
 * Runs on the build JVM only (Gradle task `compileWatFixtures`), so wabt never reaches the APK.
 * A line `;; #include <file>` is replaced by that file's text (shared guest runtime; `.wati`
 * files are never compiled on their own).
 *
 * Also emits `large_real.wasm`: wabt's own clang-built wat2wasm binary (about 1.5 MB), which
 * the wabt jar carries as a resource. It is the one realistic, compiler-produced module we
 * have without a Rust/C toolchain, so load-time and metering-pass costs are measured on it.
 */
fun main(args: Array<String>) {
    val (inDir, outDir) = args.map(::File)
    outDir.mkdirs()
    inDir.listFiles { f -> f.extension == "wat" }!!.sortedBy { it.name }.forEach { wat ->
        val text = wat.readLines().joinToString("\n") { line ->
            INCLUDE.matchEntire(line.trim())?.let { File(inDir, it.groupValues[1]).readText() } ?: line
        }
        File(outDir, wat.nameWithoutExtension + ".wasm").writeBytes(Wat2Wasm.parse(text))
    }
    val large = Wat2Wasm::class.java.getResourceAsStream("/wat2wasm")!!.use { it.readBytes() }
    File(outDir, "large_real.wasm").writeBytes(large)
}
