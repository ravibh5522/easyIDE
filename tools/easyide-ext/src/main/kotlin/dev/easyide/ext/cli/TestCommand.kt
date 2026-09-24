package dev.easyide.ext.cli

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.manifest.SemVer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** cli.md sec 5.6 `test [dir] --filter <glob> --engine <ver>`. */
object TestCommand : Command {
    override val name = "test"
    override val summary = "Replay test/*.json scenarios headlessly with the app's action runner"
    override val usage = "test [dir] [--filter <glob>] [--engine <ver>]"

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("filter", "engine"), emptySet(), 1)
        val dir = ctx.resolve(a.positional(0) ?: ".")
        if (!dir.isDirectory) ioFailure("not a directory: ${dir.path}", dir.path)
        val engine = a.value("engine") ?: CliConfig.load(ctx, dir).engine
        val api = engine?.let { SemVer.parse(it) ?: usage("--engine '$it' is not a SemVer version") } ?: AppApi.VERSION
        val v = Validator(api).validateFolder(dir, strict = false)
        val d = v.descriptor
        if (d == null) {
            ctx.out.diagnostics(v.diagnostics)
            ctx.out.line("the extension does not load; fix validate errors first")
            return ExitCode.VALIDATION
        }
        val filter = a.value("filter")?.let { g -> Regex(g.split('*').joinToString(".*") { Regex.escape(it) }) }
        val files = File(dir, "test").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .filter { filter == null || filter.matches(it.nameWithoutExtension) }
        if (files.isEmpty()) {
            ctx.out.line("no scenarios in ${File(dir, "test").path}")
            ctx.out.put("scenarios", JsonArray(emptyList()))
            return ExitCode.OK
        }
        val harness = TestHarness(d)
        val results = files.map { f ->
            val scenario = readScenario(f)
            harness.run(scenario, f.nameWithoutExtension).also { r ->
                ctx.out.line("${if (r.passed) "PASS" else "FAIL"} ${r.name}")
                r.problems.forEach { ctx.out.line("     $it") }
            }
        }
        val failed = results.count { !it.passed }
        ctx.out.put("scenarios", JsonArray(results.map { r ->
            JsonObject(mapOf("name" to JsonPrimitive(r.name), "passed" to JsonPrimitive(r.passed), "problems" to JsonArray(r.problems.map(::JsonPrimitive))))
        }))
        ctx.out.line("${results.size - failed} passed, $failed failed")
        return if (failed == 0) ExitCode.OK else ExitCode.VALIDATION
    }

    /** Scenario files are plain JSON (comments allowed); a malformed one is a usage error. */
    private fun readScenario(f: File): JsonObject =
        when (val r = JsonText.parseLenient(String(f.readBytesOrFail(), Charsets.UTF_8))) {
            is JsonParse.Ok -> r.value as? JsonObject ?: throw CliFailure(CliCode.USAGE, "${f.path}: a scenario must be a JSON object", ExitCode.VALIDATION, f.path)
            is JsonParse.Error -> throw CliFailure(CliCode.USAGE, "${f.path} line ${r.line}: ${r.message}", ExitCode.VALIDATION, f.path)
        }
}
