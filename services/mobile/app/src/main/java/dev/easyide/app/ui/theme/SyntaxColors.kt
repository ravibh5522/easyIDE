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

/** One color per [SyntaxRole]. The built-in sets live in each [Palette] (BuiltInPalettes.kt). */
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
