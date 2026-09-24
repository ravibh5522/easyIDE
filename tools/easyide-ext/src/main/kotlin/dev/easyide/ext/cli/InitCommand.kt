package dev.easyide.ext.cli

import dev.easyide.extensions.authoring.ExtensionTemplates
import java.io.File

/**
 * cli.md sec 5.1. Templates are resource directories `templates/<t>/` listed in
 * `templates/<t>/files.txt` (jar resources cannot be listed portably). Placeholders are plain
 * `{{publisher}}`, `{{name}}`, `{{displayName}}`, `{{engine}}`, `{{crate}}`, `{{year}}` substitution,
 * in file contents and in paths. Dotfiles are stored as `dot-<name>` (build tools drop real
 * dotfiles from resources). The rules live in the shared [ExtensionTemplates], which the in-app
 * "Create extension" renders with too.
 */
object InitCommand : Command {
    override val name = "init"
    override val summary = "Scaffold an extension from a template"
    override val usage = "init [dir] --template <t> [--name <n>] [--publisher <p>] [--display-name <d>] [--yes]"

    /** Every template sdk-reference names. */
    val TEMPLATES = ExtensionTemplates.ALL

    override fun run(args: List<String>, ctx: CliContext): ExitCode {
        val a = Args.parse(args, setOf("template", "name", "publisher", "display-name"), setOf("yes"), 1)
        val template = a.value("template") ?: usage("--template is required: ${TEMPLATES.joinToString(", ")}")
        if (template !in TEMPLATES) usage("unknown template '$template'; available: ${TEMPLATES.joinToString(", ")}")
        val dir = ctx.resolve(a.positional(0) ?: ".")
        val config = CliConfig.load(ctx, dir)
        val name = (a.value("name") ?: dir.canonicalFile.name).lowercase()
        val publisher = (a.value("publisher") ?: config.publisher ?: usage("--publisher is required (or set it in ~/.easyide/config.json)")).lowercase()
        val vars = ExtensionTemplates.variables(publisher, name, a.value("display-name"), java.time.Year.now().value)
            ?: usage("name and publisher must match ^${dev.easyide.extensions.manifest.ExtensionId.SEGMENT.pattern}$ (got '$publisher.$name')")

        val yes = a.flag("yes")
        if (dir.isDirectory && !dir.list().isNullOrEmpty() && !yes) {
            throw CliFailure(CliCode.EXISTS, "${dir.path} is not empty (--yes writes only missing files)", ExitCode.VALIDATION, dir.path)
        }
        val written = ArrayList<String>()
        for (rel in listing(template)) {
            val target = File(dir, ExtensionTemplates.substitute(rel, vars))
            if (target.exists()) continue
            val stored = ExtensionTemplates.storedPath(rel)
            val text = resource("templates/$template/$stored") ?: error("template $template is missing $stored")
            target.writeBytesOrFail(ExtensionTemplates.substitute(text, vars).toByteArray(Charsets.UTF_8))
            written += ExtensionTemplates.substitute(rel, vars)
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
        ExtensionTemplates.listing(resource("templates/$template/${ExtensionTemplates.LISTING_FILE}") ?: error("template $template has no files.txt"))

    private fun resource(path: String): String? =
        InitCommand::class.java.getResourceAsStream("/$path")?.use { String(it.readBytes(), Charsets.UTF_8) }
}
