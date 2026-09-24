package dev.easyide.ext.cli

import dev.easyide.extensions.AppApi
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

val COMMANDS: List<Command> = listOf(InitCommand, ValidateCommand, PackageCommand, KeygenCommand, SignCommand, VerifyCommand, PublishCommand, RegistryCommand)

/** Not built yet; named so authors get a clear answer instead of "unknown command". */
private val PLANNED = mapOf(
    "test" to "M5 test harness", "dev" to "M5 dev loop",
)

fun main(argv: Array<String>) {
    exitProcess(run(argv.toList(), System.out, System.err, File(System.getProperty("user.dir")), File(System.getProperty("user.home"))))
}

/** Entry point shared with in-process tests. Returns the process exit code. */
fun run(argv: List<String>, stdout: PrintStream, stderr: PrintStream, cwd: File, home: File, env: Map<String, String> = System.getenv(), readSecret: ((String) -> CharArray?)? = null): Int {
    val json = "--json" in argv
    val out = Output(stdout, stderr, json)
    val ctx = if (readSecret == null) CliContext(cwd, home, out, env) else CliContext(cwd, home, out, env, readSecret)
    val name = argv.firstOrNull { !it.startsWith("--") }
    if (name == null || name == "help") {
        help(stdout)
        return if (name == null && "--help" !in argv && "--version" !in argv) ExitCode.VALIDATION.code else ExitCode.OK.code
    }
    val command = COMMANDS.firstOrNull { it.name == name }
    val rest = argv.toMutableList().apply { remove(name) }
    val exit = try {
        when {
            command == null && name in PLANNED -> throw CliFailure(CliCode.USAGE, "'$name' is planned (${PLANNED[name]}) but not implemented in this version", ExitCode.VALIDATION)
            command == null -> usage("unknown command '$name'; run easyide-ext help")
            "--help" in rest -> { out.line("usage: easyide-ext ${command.usage}"); ExitCode.OK }
            else -> command.run(rest, ctx)
        }
    } catch (e: CliFailure) {
        out.diagnostic(cliError(e.code, e.message ?: e.code, e.file))
        if (e.code == CliCode.USAGE && command != null) out.line("usage: easyide-ext ${command.usage}")
        e.exit
    }
    out.finish(name, exit)
    return exit.code
}

private fun help(out: PrintStream) {
    out.println("easyide-ext ${Main.VERSION} - easyIDE extension author tool (extension API ${AppApi.VERSION})")
    out.println()
    COMMANDS.forEach { out.println("  %-9s %s".format(it.name, it.summary)) }
    out.println()
    out.println("Every command takes --json (one JSON object on stdout). Exit codes: 0 ok, 1 validation/usage, 2 I/O.")
    out.println("Not yet implemented: ${PLANNED.keys.joinToString(", ")}.")
}

object Main {
    val VERSION: String = Main::class.java.`package`?.implementationVersion ?: "dev"
}
