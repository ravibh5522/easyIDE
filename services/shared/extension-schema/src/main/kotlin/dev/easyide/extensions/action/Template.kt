package dev.easyide.extensions.action

/** The sdk-reference "Variables" without an argument. */
enum class PredefinedVariable(val wire: String) {
    WORKSPACE_FOLDER("workspaceFolder"),
    WORKSPACE_FOLDER_BASENAME("workspaceFolderBasename"),
    FILE("file"),
    RELATIVE_FILE("relativeFile"),
    FILE_BASENAME("fileBasename"),
    FILE_BASENAME_NO_EXTENSION("fileBasenameNoExtension"),
    FILE_EXTNAME("fileExtname"),
    FILE_DIRNAME("fileDirname"),
    RELATIVE_FILE_DIRNAME("relativeFileDirname"),
    FILE_WORKSPACE_FOLDER("fileWorkspaceFolder"),
    LINE_NUMBER("lineNumber"),
    COLUMN("column"),
    SELECTED_TEXT("selectedText"),
    CURRENT_WORD("currentWord"),
    LINE_TEXT("lineText"),
    LANGUAGE_ID("languageId"),
    CWD("cwd"),
    PATH_SEPARATOR("pathSeparator"),
    EXTENSION_PATH("extensionPath"),
    ENV_ID("envId"),
    ENV_NAME("envName");

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }
        fun parse(wire: String): PredefinedVariable? = BY_WIRE[wire]
    }
}

/** One `${...}` reference inside a [Template]. */
sealed interface VariableRef {
    data class Predefined(val variable: PredefinedVariable) : VariableRef
    data class Env(val name: String) : VariableRef
    data class Config(val key: String) : VariableRef
    data class Command(val id: String) : VariableRef
    data class Input(val id: String) : VariableRef
    data class Result(val name: String) : VariableRef
}

/**
 * A string parameter split at load into literal text and variable references, so a
 * malformed `${...}` is a validation error instead of a surprise at run time, and quoting
 * can treat literal text (the author's) differently from substituted values (data).
 */
class Template private constructor(val source: String, val segments: List<Segment>) {

    sealed interface Segment {
        data class Literal(val text: String) : Segment
        data class Var(val ref: VariableRef) : Segment
    }

    val variables: List<VariableRef> get() = segments.mapNotNull { (it as? Segment.Var)?.ref }

    /** The whole template is exactly one variable: JSON values then pass through unconverted. */
    val singleVariable: VariableRef? get() = (segments.singleOrNull() as? Segment.Var)?.ref

    override fun equals(other: Any?): Boolean = other is Template && other.source == source
    override fun hashCode(): Int = source.hashCode()
    override fun toString(): String = source

    sealed interface Parse {
        data class Ok(val template: Template) : Parse
        data class Error(val offset: Int, val message: String) : Parse
    }

    companion object {
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun literal(text: String): Template = Template(text, if (text.isEmpty()) emptyList() else listOf(Segment.Literal(text)))

        fun parse(text: String): Parse {
            val segments = ArrayList<Segment>()
            val lit = StringBuilder()
            var i = 0
            while (i < text.length) {
                if (text[i] == '$' && text.getOrNull(i + 1) == '{') {
                    val end = text.indexOf('}', i + 2)
                    if (end < 0) return Parse.Error(i, "unterminated \${")
                    val ref = parseRef(text.substring(i + 2, end)) ?: return Parse.Error(i, "unknown variable \${${text.substring(i + 2, end)}}")
                    if (lit.isNotEmpty()) { segments += Segment.Literal(lit.toString()); lit.clear() }
                    segments += Segment.Var(ref)
                    i = end + 1
                } else {
                    lit.append(text[i]); i++
                }
            }
            if (lit.isNotEmpty()) segments += Segment.Literal(lit.toString())
            return Parse.Ok(Template(text, segments))
        }

        private fun parseRef(body: String): VariableRef? {
            val colon = body.indexOf(':')
            if (colon < 0) return PredefinedVariable.parse(body)?.let(VariableRef::Predefined)
            val arg = body.substring(colon + 1)
            if (arg.isEmpty() || arg.any { it.isWhitespace() }) return null
            return when (body.substring(0, colon)) {
                "env" -> if (ENV_NAME.matches(arg)) VariableRef.Env(arg) else null
                "config" -> VariableRef.Config(arg)
                "command" -> VariableRef.Command(arg)
                "input" -> VariableRef.Input(arg)
                "result" -> VariableRef.Result(arg)
                else -> null
            }
        }
    }
}
