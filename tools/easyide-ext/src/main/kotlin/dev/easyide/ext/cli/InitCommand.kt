package dev.easyide.ext.cli

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.manifest.ExtensionId
import java.io.File

/**
 * cli.md sec 5.1. Templates are resource directories `templates/<t>/` listed in
 * `templates/<t>/files.txt` (jar resources cannot be listed portably). Placeholders are plain
 * `{{publisher}}`, `{{name}}`, `{{displayName}}`, `{{engine}}` substitution, in file contents
 * and in paths. Dotfiles are stored as `dot-<name>` (build tools drop real dotfiles from
 * resources). The same templates back the in-app "Create extension".
 */
object InitCommand : Command {
    override val name = "init"
    override val summary = "Scaffold an extension from a template"
    override val usage = "init [dir] --template <t> [--name <n>] [--publisher <p>] [--display-name <d>] [--yes]"

    /** Every template sdk-reference names. */
    val TEMPLATES = listOf("theme", "snippets", "language-pack", "lsp-pack", "toolbar-command", "wasm-rust", "wasm-assemblyscript")

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("template", "name", "publisher", "display-name"), setOf("yes"), 1)
        val template = a.value("template") ?: usage("--template is required: ${TEMPLATES.joinToString(", ")}")
        if (template !in TEMPLATES) usage("unknown template '$template'; available: ${TEMPLATES.joinToString(", ")}")
        val dir = ctx.resolve(a.positional(0) ?: ".")
        val config = CliConfig.load(ctx, dir)
        val name = (a.value("name") ?: dir.canonicalFile.name).lowercase()
        val publisher = (a.value("publisher") ?: config.publisher ?: usage("--publisher is required (or set it in ~/.easyide/config.json)")).lowercase()
        ExtensionId.of(publisher, name) ?: usage("name and publisher must match ^${ExtensionId.SEGMENT.pattern}$ (got '$publisher.$name')")
        val displayName = a.value("display-name") ?: name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val vars = mapOf(
            "publisher" to publisher, "name" to name, "displayName" to displayName, "crate" to name.replace('-', '_'),
            "engine" to "^${AppApi.VERSION}", "year" to java.time.Year.now().toString(),
        )

        val yes = a.flag("yes")
        if (dir.isDirectory && !dir.list().isNullOrEmpty() && !yes) {
            throw CliFailure(CliCode.EXISTS, "${dir.path} is not empty (--yes writes only missing files)", ExitCode.VALIDATION, dir.path)
        }
        val written = ArrayList<String>()
        for (rel in listing(template)) {
            val target = File(dir, substitute(rel, vars))
            if (target.exists()) continue
            val stored = rel.split('/').joinToString("/") { if (it.startsWith(".")) "dot-" + it.substring(1) else it }
            val text = resource("templates/$template/$stored") ?: error("template $template is missing $stored")
            target.writeBytesOrFail(substitute(text, vars).toByteArray(Charsets.UTF_8))
            written += substitute(rel, vars)
        }
        ctx.out.put("dir", dir.path)
        ctx.out.put("id", "$publisher.$name")
        ctx.out.put("files", kotlinx.serialization.json.JsonArray(written.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        ctx.out.line("created $publisher.$name from '$template' in ${dir.path}")
        written.forEach { ctx.out.line("  $it") }
        ctx.out.line("next: easyide-ext validate ${a.positional(0) ?: "."} --strict")
        return ExitCode.OK
    }

    fun listing(template: String): List<String> =
        (resource("templates/$template/files.txt") ?: error("template $template has no files.txt")).lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun resource(path: String): String? =
        InitCommand::class.java.getResourceAsStream("/$path")?.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun substitute(s: String, vars: Map<String, String>): String =
        vars.entries.fold(s) { acc, (k, v) -> acc.replace("{{$k}}", v) }
}
