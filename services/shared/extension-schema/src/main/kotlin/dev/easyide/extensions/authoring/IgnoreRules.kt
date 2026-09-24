package dev.easyide.extensions.authoring

/**
 * What goes into a package built from a source folder (cli.md sec 5.3): the default excludes
 * plus the folder's `.easyextignore`. Shared so `easyide-ext package`/`dev` and the app's
 * developer installs from a folder drop exactly the same files.
 */
object PackageIgnores {
    const val IGNORE_FILE = ".easyextignore"

    /** The CLI's per-extension config; never package content. */
    const val PROJECT_FILE = ".easyide-ext.json"

    /** cli.md sec 5.3 excludes, plus author-side files that are never package content. */
    val DEFAULT: List<String> = listOf(
        ".git/", "test/", "guest/", "target/", "node_modules/", "dist/",
        ".gitignore", IGNORE_FILE, PROJECT_FILE, "*.key", "*.easyext", "*.easyext.sig", ".DS_Store",
    )

    /** [DEFAULT] plus the lines of an `.easyextignore` ([ignoreFile] null when there is none). */
    fun rules(ignoreFile: String?): IgnoreRules = IgnoreRules(DEFAULT + ignoreFile?.lines().orEmpty())
}

/**
 * The gitignore subset of cli.md sec 5.3: `*`, `**`, `/` anchoring, trailing `/` for
 * directories and `!` negation; the last matching rule wins. Paths are package-relative, and
 * directories are passed with a trailing `/`.
 */
class IgnoreRules(lines: List<String>) {
    private class Rule(val regex: Regex, val negate: Boolean, val dirOnly: Boolean)

    private val rules: List<Rule> = lines.mapNotNull { raw ->
        var p = raw.trim()
        if (p.isEmpty() || p.startsWith("#")) return@mapNotNull null
        val negate = p.startsWith("!")
        if (negate) p = p.substring(1)
        val dirOnly = p.endsWith("/")
        p = p.trimEnd('/')
        val anchored = p.contains('/')
        p = p.trimStart('/')
        if (p.isEmpty()) return@mapNotNull null
        val body = globToRegex(p)
        Rule(Regex(if (anchored) "^$body(/.*)?$" else "^(.*/)?$body(/.*)?$"), negate, dirOnly)
    }

    fun ignored(path: String): Boolean {
        val isDir = path.endsWith("/")
        val p = path.trimEnd('/')
        var result = false
        for (r in rules) {
            // A directory-only rule matches the directory itself and anything below it.
            val m = r.regex.matchEntire(p) ?: continue
            if (r.dirOnly && !isDir && m.groupValues.last().isEmpty()) continue
            result = !r.negate
        }
        return result
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }
}
