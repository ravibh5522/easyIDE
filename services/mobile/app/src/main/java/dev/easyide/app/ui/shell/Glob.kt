package dev.easyide.app.ui.shell

/**
 * The glob dialect of `documentOpeners` and `workbench.editorAssociations`: `*` within a segment,
 * `**` across segments (a `**` followed by a slash may match no directory at all), `?` one character. Like VS Code, a pattern with
 * no slash matches the file name alone, so `*.md` means "any Markdown file".
 */
internal class Glob private constructor(private val regex: Regex, private val nameOnly: Boolean) {

    fun matches(uri: DocumentUri): Boolean =
        regex.matches(if (nameOnly) uri.name else uri.segments.joinToString("/"))

    companion object {
        fun compile(glob: String): Glob {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when {
                    glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                    glob.startsWith("**", i) -> { out.append(".*"); i += 2 }
                    glob[i] == '*' -> { out.append("[^/]*"); i++ }
                    glob[i] == '?' -> { out.append("[^/]"); i++ }
                    else -> { out.append(Regex.escape(glob[i].toString())); i++ }
                }
            }
            return Glob(Regex(out.toString()), nameOnly = '/' !in glob)
        }
    }
}
