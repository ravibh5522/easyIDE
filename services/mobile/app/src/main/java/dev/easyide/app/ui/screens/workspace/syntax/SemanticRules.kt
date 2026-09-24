package dev.easyide.app.ui.screens.workspace.syntax

import dev.easyide.app.ui.theme.SyntaxRole

/**
 * Collapses LSP semantic token `(type, modifiers)` onto [SyntaxRole]s (lsp-features.md 4.13),
 * the semantic sibling of [ScopeRules] and, like it, the single source for that mapping.
 *
 * [TOKEN_TYPES] and [TOKEN_MODIFIERS] are what the client advertises in
 * `textDocument.semanticTokens`, so a server only sends types this table can colour. Types
 * mapped to null (none today) would be advertised for theme selectors only.
 */
internal object SemanticRules {

    /** LSP 3.17 standard token types -> role. Order is the advertised order. */
    private val TYPES: List<Pair<String, SyntaxRole>> = listOf(
        "namespace" to SyntaxRole.NAMESPACE,
        "type" to SyntaxRole.TYPE,
        "class" to SyntaxRole.TYPE,
        "enum" to SyntaxRole.TYPE,
        "interface" to SyntaxRole.TYPE,
        "struct" to SyntaxRole.TYPE,
        "typeParameter" to SyntaxRole.TYPE,
        "parameter" to SyntaxRole.PARAMETER,
        "variable" to SyntaxRole.VARIABLE,
        "property" to SyntaxRole.PROPERTY,
        "enumMember" to SyntaxRole.CONSTANT,
        "event" to SyntaxRole.PROPERTY,
        "function" to SyntaxRole.FUNCTION,
        "method" to SyntaxRole.FUNCTION,
        "macro" to SyntaxRole.FUNCTION,
        "keyword" to SyntaxRole.KEYWORD,
        "modifier" to SyntaxRole.KEYWORD,
        "comment" to SyntaxRole.COMMENT,
        "string" to SyntaxRole.STRING,
        "number" to SyntaxRole.NUMBER,
        "regexp" to SyntaxRole.REGEXP,
        "operator" to SyntaxRole.OPERATOR,
        "decorator" to SyntaxRole.ATTRIBUTE,
        "label" to SyntaxRole.CONSTANT,
    )

    private val BY_TYPE: Map<String, SyntaxRole> = TYPES.toMap()

    /** Advertised token types (the table's keys). */
    val TOKEN_TYPES: List<String> = TYPES.map { it.first }

    /**
     * Advertised modifiers: the LSP 3.17 standard set. Only `readonly` changes a role here;
     * the rest are advertised so theme selectors such as `*.deprecated` can match them.
     */
    val TOKEN_MODIFIERS: List<String> = listOf(
        "declaration", "definition", "readonly", "static", "deprecated", "abstract", "async",
        "modification", "documentation", "defaultLibrary",
    )

    /**
     * The role for a token, or null to keep the TextMate colour underneath (an unknown type
     * from a server that ignored the advertised list).
     */
    fun roleFor(type: String, modifiers: Set<String>): SyntaxRole? {
        val role = BY_TYPE[type] ?: return null
        // A readonly variable is a constant in every theme's vocabulary (VS Code maps it to
        // `variable.other.constant`, which ScopeRules also colours as CONSTANT).
        return if (role == SyntaxRole.VARIABLE && "readonly" in modifiers) SyntaxRole.CONSTANT else role
    }
}
