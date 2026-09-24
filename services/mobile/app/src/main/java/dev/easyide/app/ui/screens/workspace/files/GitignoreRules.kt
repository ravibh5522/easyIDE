package dev.easyide.app.ui.screens.workspace.files

/**
 * The patterns of one `.gitignore`, with git's semantics for the parts a file explorer and a
 * file finder need: comments and blank lines, `!` negation, trailing `/` (directories only),
 * anchoring by a leading or inner `/`, and the wildcards `*`, `?`, `[...]` and `**`.
 * Character-class ranges pass through to the regex engine unchanged.
 *
 * Paths given to [verdict] are relative to the directory that holds the file.
 */
class GitignoreRules private constructor(private val rules: List<Rule>) {

    private class Rule(val regex: Regex, val negated: Boolean, val directoriesOnly: Boolean)

    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * True when the last matching pattern ignores [path], false when a `!` pattern re-includes
     * it, null when no pattern matches (the caller falls back to rules of enclosing directories).
     */
    fun verdict(path: String, isDirectory: Boolean): Boolean? {
        for (rule in rules.asReversed()) {
            if (rule.directoriesOnly && !isDirectory) continue
            if (rule.regex.matches(path)) return !rule.negated
        }
        return null
    }

    companion object {
        val EMPTY = GitignoreRules(emptyList())

        fun parse(text: String): GitignoreRules {
            val rules = text.lineSequence().mapNotNull(::parseLine).toList()
            return if (rules.isEmpty()) EMPTY else GitignoreRules(rules)
        }

        private fun parseLine(raw: String): Rule? {
            var line = raw.trimEnd('\r')
            // Trailing spaces are insignificant unless escaped with a backslash.
            while (line.endsWith(' ') && !line.endsWith("\\ ")) line = line.dropLast(1)
            if (line.isEmpty() || line.startsWith('#')) return null
            val negated = line.startsWith('!')
            if (negated) line = line.substring(1)
            if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.substring(1)
            val directoriesOnly = line.endsWith('/')
            if (directoriesOnly) line = line.dropLast(1)
            if (line.isEmpty()) return null
            val anchored = line.contains('/')
            line = line.removePrefix("/")
            val body = globToRegex(line)
            val source = if (anchored) body else "(?:.*/)?$body"
            return Rule(Regex(source), negated, directoriesOnly)
        }

        private fun globToRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                    c == '*' && glob.startsWith("**", i) && i + 2 == glob.length -> { out.append(".*"); i += 2 }
                    c == '*' -> { out.append("[^/]*"); i++ }
                    c == '?' -> { out.append("[^/]"); i++ }
                    c == '\\' && i + 1 < glob.length -> { out.append(Regex.escape(glob[i + 1].toString())); i += 2 }
                    c == '[' -> {
                        val close = glob.indexOf(']', i + 2)
                        if (close < 0) { out.append("\\["); i++ } else {
                            val cls = glob.substring(i + 1, close).let { if (it.startsWith('!')) "^" + it.substring(1) else it }
                            out.append('[').append(cls.replace("\\", "\\\\")).append(']')
                            i = close + 1
                        }
                    }
                    else -> { out.append(Regex.escape(c.toString())); i++ }
                }
            }
            return out.toString()
        }
    }
}

/**
 * The `.gitignore` files of a project, by the directory that holds them ("" is the root), and
 * the question the explorer and quick open ask: is this path ignored?
 *
 * Follows git: a path inside an ignored directory is ignored whatever its own rules say (git
 * never descends into it), and otherwise the deepest `.gitignore` with a matching pattern
 * decides.
 */
class IgnoreIndex(private val rulesByDir: Map<String, GitignoreRules>) {

    val directories: Set<String> get() = rulesByDir.keys

    fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (verdictOf(path.substring(0, slash), isDirectory = true) == true) return true
            slash = path.indexOf('/', slash + 1)
        }
        return verdictOf(path, isDirectory) == true
    }

    private fun verdictOf(path: String, isDirectory: Boolean): Boolean? {
        var verdict: Boolean? = null
        var dir = ""
        val segments = path.split('/')
        // Root first, so a deeper file's verdict overrides a shallower one.
        for (depth in 0 until segments.size) {
            if (depth > 0) dir = if (dir.isEmpty()) segments[depth - 1] else "$dir/${segments[depth - 1]}"
            val rules = rulesByDir[dir] ?: continue
            val relative = segments.drop(depth).joinToString("/")
            rules.verdict(relative, isDirectory)?.let { verdict = it }
        }
        return verdict
    }

    fun with(dir: String, rules: GitignoreRules): IgnoreIndex = IgnoreIndex(rulesByDir + (dir to rules))

    fun without(dir: String): IgnoreIndex = IgnoreIndex(rulesByDir - dir)

    companion object {
        val EMPTY = IgnoreIndex(emptyMap())
    }
}
