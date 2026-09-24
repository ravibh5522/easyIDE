package dev.easyide.app.ui.screens.workspace.edit

/**
 * Hand-built configs mirroring the bundled VS Code ones for the M1 languages,
 * and a `|` caret notation (`[` `]` for a selection) so cases read as text.
 */
internal object Fixtures {

    private val brackets = listOf(CharPair("{", "}"), CharPair("[", "]"), CharPair("(", ")"))

    val python = LanguageConfig(
        lineComment = "#",
        blockComment = "\"\"\"" to "\"\"\"",
        brackets = brackets,
        autoClosingPairs = brackets + listOf(
            CharPair("\"", "\"", setOf(SCOPE_STRING)),
            CharPair("f\"", "\"", setOf(SCOPE_STRING, SCOPE_COMMENT)),
            CharPair("'", "'", setOf(SCOPE_STRING, SCOPE_COMMENT)),
        ),
        surroundingPairs = brackets + listOf(CharPair("\"", "\""), CharPair("'", "'")),
        onEnterRules = listOf(
            EnterRule(
                beforeText = compileJsRegex("^\\s*(?:def|class|for|if|elif|else|while|try|with|finally|except|async).*?:\\s*$", "")!!,
                indent = IndentAction.INDENT,
            ),
        ),
    )

    val typescript = LanguageConfig(
        lineComment = "//",
        blockComment = "/*" to "*/",
        brackets = brackets,
        autoClosingPairs = brackets + listOf(
            CharPair("'", "'", setOf(SCOPE_STRING, SCOPE_COMMENT)),
            CharPair("\"", "\"", setOf(SCOPE_STRING)),
            CharPair("/**", " */", setOf(SCOPE_STRING)),
        ),
        surroundingPairs = brackets,
        autoCloseBefore = ";:.,=}])>` \n\t",
        onEnterRules = listOf(
            EnterRule(
                beforeText = compileJsRegex("^\\s*/\\*\\*(?!/)([^\\*]|\\*(?!/))*$", "")!!,
                afterText = compileJsRegex("^\\s*\\*/$", "")!!,
                indent = IndentAction.INDENT_OUTDENT,
                appendText = " * ",
            ),
            EnterRule(
                beforeText = compileJsRegex("^.*\\{[^\\}]*$", "")!!,
                afterText = compileJsRegex("^\\s*\\}.*$", "")!!,
                indent = IndentAction.INDENT_OUTDENT,
                appendText = "\t",
            ),
        ),
    )

    val markdown = LanguageConfig(blockComment = "<!--" to "-->", brackets = brackets)

    fun state(marked: String): TextState {
        val caret = marked.indexOf('|')
        if (caret >= 0) return TextState(marked.removeRange(caret, caret + 1), caret)
        val start = marked.indexOf('[')
        val end = marked.indexOf(']') - 1
        return TextState(marked.replace("[", "").replace("]", ""), start, end)
    }

    fun render(state: TextState): String =
        if (state.collapsed) StringBuilder(state.text).insert(state.min, '|').toString()
        else StringBuilder(state.text).insert(state.max, ']').insert(state.min, '[').toString()
}
