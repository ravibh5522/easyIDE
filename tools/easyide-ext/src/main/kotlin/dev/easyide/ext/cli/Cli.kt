package dev.easyide.ext.cli

import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.PrintStream

/** sdk-reference exit codes: 0 ok, 1 validation or usage error, 2 I/O or network error. */
enum class ExitCode(val code: Int) { OK(0), VALIDATION(1), IO(2) }

/** Stable CLI-only diagnostic ids; manifest ids come from `DiagnosticCode` in the shared core. */
object CliCode {
    const val USAGE = "E_USAGE"
    const val IO = "E_IO"
    const val EXISTS = "E_EXISTS"
    const val KEY = "E_KEY"
    const val KEY_MISMATCH = "E_KEY_MISMATCH"
    const val SIGNATURE = "E_SIGNATURE"
    const val WASM_MODULE = "E_WASM_MODULE"
    const val STRICT = "E_STRICT"
    const val README_MISSING = "W_README_MISSING"
    const val LICENSE_MISSING = "W_LICENSE_MISSING"
    const val LICENSE_FIELD = "W_LICENSE_FIELD"
    const val ICON_SIZE = "W_ICON_SIZE"
    const val CONFIG_KEY = "W_CONFIG_KEY"
}

/** A boundary failure (filesystem, subprocess) or a usage error, mapped to one diagnostic and an exit code. */
class CliFailure(val code: String, message: String, val exit: ExitCode, val file: String = "") : Exception(message)

fun usage(message: String): Nothing = throw CliFailure(CliCode.USAGE, message, ExitCode.VALIDATION)

fun ioFailure(message: String, file: String = ""): Nothing = throw CliFailure(CliCode.IO, message, ExitCode.IO, file)

/** Everything a command touches, so tests can run commands in-process against temp dirs. */
class CliContext(
    val cwd: File,
    val home: File,
    val out: Output,
    val env: Map<String, String> = System.getenv(),
    /** Reads a passphrase from the terminal; null when there is none (CI, tests). */
    val readSecret: (prompt: String) -> CharArray? = { p -> System.console()?.readPassword(p) },
) {
    fun resolve(path: String): File {
        val expanded = if (path == "~" || path.startsWith("~/")) home.path + path.substring(1) else path
        val f = File(expanded)
        return if (f.isAbsolute) f else File(cwd, expanded)
    }
}

/**
 * Human text goes to [human]; with `--json` stdout carries exactly one object
 * `{"ok","command","diagnostics","result"}` and the text moves to stderr (cli.md sec 3).
 */
class Output(private val stdout: PrintStream, private val stderr: PrintStream, val json: Boolean) {
    private val diagnostics = ArrayList<Diagnostic>()
    private val result = LinkedHashMap<String, JsonElement>()
    private val human: PrintStream get() = if (json) stderr else stdout

    fun line(s: String) = human.println(s)

    fun diagnostic(d: Diagnostic) {
        diagnostics += d
        val where = listOf(d.file, d.pointer.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()).joinToString("").ifEmpty { "-" }
        val label = if (d.severity == Severity.ERROR) "error" else "warning"
        stderr.println("$label ${d.code} $where: ${d.message}")
    }

    fun diagnostics(ds: List<Diagnostic>) = ds.forEach(::diagnostic)

    fun put(key: String, value: JsonElement) { result[key] = value }
    fun put(key: String, value: String) = put(key, JsonPrimitive(value))
    fun put(key: String, value: Number) = put(key, JsonPrimitive(value))
    fun put(key: String, value: Boolean) = put(key, JsonPrimitive(value))

    val errors: Int get() = diagnostics.count { it.severity == Severity.ERROR }
    val warnings: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    fun finish(command: String, exit: ExitCode) {
        if (!json) return
        val doc = JsonObject(
            mapOf(
                "ok" to JsonPrimitive(exit == ExitCode.OK),
                "command" to JsonPrimitive(command),
                "diagnostics" to JsonArray(diagnostics.map { it.toJson() }),
                "result" to JsonObject(result),
            ),
        )
        stdout.println(doc.toString())
    }
}

fun Diagnostic.toJson(): JsonObject = JsonObject(
    mapOf(
        "code" to JsonPrimitive(code), "severity" to JsonPrimitive(severity.name.lowercase()),
        "file" to JsonPrimitive(file), "pointer" to JsonPrimitive(pointer), "message" to JsonPrimitive(message),
    ),
)

fun cliError(code: String, message: String, file: String = "") = Diagnostic(Severity.ERROR, code, "", file, message)

fun cliWarning(code: String, message: String, file: String = "") = Diagnostic(Severity.WARNING, code, "", file, message)

/** I/O boundary helpers: every filesystem failure becomes exit 2 with the path named. */
fun File.readBytesOrFail(): ByteArray = try { readBytes() } catch (e: IOException) { ioFailure("cannot read $path: ${e.message}", path) }

fun File.writeBytesOrFail(bytes: ByteArray) {
    try {
        parentFile?.mkdirs()
        writeBytes(bytes)
    } catch (e: IOException) {
        ioFailure("cannot write $path: ${e.message}", path)
    }
}
