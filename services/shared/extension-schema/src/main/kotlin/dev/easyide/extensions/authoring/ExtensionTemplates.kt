package dev.easyide.extensions.authoring

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.PackagePaths

/** One file of a rendered template: a normalised relative path and its text. */
data class TemplateFile(val path: String, val text: String)

/**
 * The extension templates of `services/shared/extension-templates` (cli.md sec 5.1), rendered
 * the same way by `easyide-ext init` and the app's "Create extension". Each template is a
 * directory with a `files.txt` listing (jar resources and app assets cannot be listed
 * portably); dotfiles are stored as `dot-<name>`; `{{publisher}}`, `{{name}}`,
 * `{{displayName}}`, `{{engine}}`, `{{crate}}` and `{{year}}` are substituted in contents and
 * paths. Pure: the caller supplies the reader and writes the result.
 */
object ExtensionTemplates {
    /** Every template sdk-reference names. */
    val ALL: List<String> = listOf("theme", "snippets", "language-pack", "lsp-pack", "toolbar-command", "wasm-rust", "wasm-assemblyscript")

    /** Templates that need no toolchain and no build-time generated files: the ones the app offers. */
    val DECLARATIVE: List<String> = listOf("theme", "snippets", "language-pack", "lsp-pack", "toolbar-command")

    const val LISTING_FILE = "files.txt"
    private const val DOT_PREFIX = "dot-"

    /** "my-theme" -> "My Theme", the default `displayName`. */
    fun defaultDisplayName(name: String): String = name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /**
     * The substitution variables, or null when `<publisher>.<name>` is not a valid id
     * (both are lowercased first, as `init` does). A blank [displayName] means the default.
     */
    fun variables(publisher: String, name: String, displayName: String?, year: Int): Map<String, String>? {
        val p = publisher.trim().lowercase()
        val n = name.trim().lowercase()
        ExtensionId.of(p, n) ?: return null
        return mapOf(
            "publisher" to p, "name" to n,
            "displayName" to (displayName?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDisplayName(n)),
            "crate" to n.replace('-', '_'),
            "engine" to "^${AppApi.VERSION}", "year" to year.toString(),
        )
    }

    /** The paths a `files.txt` lists, unsubstituted. */
    fun listing(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Where a listed path is stored: every dotfile segment as `dot-<name>`. */
    fun storedPath(listed: String): String =
        listed.split('/').joinToString("/") { if (it.startsWith(".")) DOT_PREFIX + it.substring(1) else it }

    fun substitute(s: String, vars: Map<String, String>): String =
        vars.entries.fold(s) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    /**
     * Renders [template]: [read] returns the stored file at a template-relative path (the
     * listing is `files.txt`), or null when it is missing.
     * @throws IllegalStateException for a missing listing or file, or a path that would not
     *   stay inside the target folder.
     */
    fun render(template: String, vars: Map<String, String>, read: (String) -> String?): List<TemplateFile> {
        val listed = listing(read(LISTING_FILE) ?: error("template $template has no $LISTING_FILE"))
        return listed.map { rel ->
            val path = substitute(rel, vars)
            check(PackagePaths.normalize(path) == path) { "template $template lists an invalid path: $rel" }
            val stored = storedPath(rel)
            TemplateFile(path, substitute(read(stored) ?: error("template $template is missing $stored"), vars))
        }
    }
}
