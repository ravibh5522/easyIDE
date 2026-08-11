package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The semantic roles a syntax token can carry.
 *
 * TextMate grammars emit hundreds of distinct scope strings; painting them
 * directly would put grammar-specific names in the theme. Scopes are collapsed
 * to these roles by [dev.easyide.app.ui.screens.workspace.syntax.ScopeRules],
 * so a theme defines ~20 colors rather than tracking every grammar.
 */
enum class SyntaxRole {
    PLAIN,
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    TYPE,
    FUNCTION,
    VARIABLE,
    PARAMETER,
    PROPERTY,
    CONSTANT,
    OPERATOR,
    PUNCTUATION,
    TAG,
    ATTRIBUTE,
    NAMESPACE,
    REGEXP,
    ESCAPE,
    HEADING,
    LINK,
    INVALID,
}

/** One color per [SyntaxRole]. Paired light/dark sets live below. */
data class SyntaxColors(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val variable: Color,
    val parameter: Color,
    val property: Color,
    val constant: Color,
    val operator: Color,
    val punctuation: Color,
    val tag: Color,
    val attribute: Color,
    val namespace: Color,
    val regexp: Color,
    val escape: Color,
    val heading: Color,
    val link: Color,
    val invalid: Color,
) {
    operator fun get(role: SyntaxRole): Color = when (role) {
        SyntaxRole.PLAIN -> plain
        SyntaxRole.KEYWORD -> keyword
        SyntaxRole.STRING -> string
        SyntaxRole.COMMENT -> comment
        SyntaxRole.NUMBER -> number
        SyntaxRole.TYPE -> type
        SyntaxRole.FUNCTION -> function
        SyntaxRole.VARIABLE -> variable
        SyntaxRole.PARAMETER -> parameter
        SyntaxRole.PROPERTY -> property
        SyntaxRole.CONSTANT -> constant
        SyntaxRole.OPERATOR -> operator
        SyntaxRole.PUNCTUATION -> punctuation
        SyntaxRole.TAG -> tag
        SyntaxRole.ATTRIBUTE -> attribute
        SyntaxRole.NAMESPACE -> namespace
        SyntaxRole.REGEXP -> regexp
        SyntaxRole.ESCAPE -> escape
        SyntaxRole.HEADING -> heading
        SyntaxRole.LINK -> link
        SyntaxRole.INVALID -> invalid
    }
}

internal val DarkSyntaxColors = SyntaxColors(
    plain = Color(0xFFD4D4D4),
    keyword = Color(0xFF569CD6),
    string = Color(0xFFCE9178),
    comment = Color(0xFF6A9955),
    number = Color(0xFFB5CEA8),
    type = Color(0xFF4EC9B0),
    function = Color(0xFFDCDCAA),
    variable = Color(0xFF9CDCFE),
    parameter = Color(0xFF9CDCFE),
    property = Color(0xFF9CDCFE),
    constant = Color(0xFF4FC1FF),
    operator = Color(0xFFD4D4D4),
    punctuation = Color(0xFF9A9A9A),
    tag = Color(0xFF569CD6),
    attribute = Color(0xFF9CDCFE),
    namespace = Color(0xFF4EC9B0),
    regexp = Color(0xFFD16969),
    escape = Color(0xFFD7BA7D),
    heading = Color(0xFF569CD6),
    link = Color(0xFF3794FF),
    invalid = Color(0xFFF44747),
)

internal val LightSyntaxColors = SyntaxColors(
    plain = Color(0xFF000000),
    keyword = Color(0xFF0000FF),
    string = Color(0xFFA31515),
    comment = Color(0xFF008000),
    number = Color(0xFF098658),
    type = Color(0xFF267F99),
    function = Color(0xFF795E26),
    variable = Color(0xFF001080),
    parameter = Color(0xFF001080),
    property = Color(0xFF001080),
    constant = Color(0xFF0070C1),
    operator = Color(0xFF000000),
    punctuation = Color(0xFF555555),
    tag = Color(0xFF800000),
    attribute = Color(0xFFE50000),
    namespace = Color(0xFF267F99),
    regexp = Color(0xFF811F3F),
    escape = Color(0xFFEE0000),
    heading = Color(0xFF800000),
    link = Color(0xFF0066BF),
    invalid = Color(0xFFCD3131),
)
