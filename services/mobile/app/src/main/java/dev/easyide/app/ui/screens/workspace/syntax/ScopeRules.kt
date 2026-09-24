package dev.easyide.app.ui.screens.workspace.syntax

import dev.easyide.app.ui.theme.SyntaxRole

/**
 * Collapses TextMate scope strings onto [SyntaxRole]s.
 *
 * A grammar labels a token with a stack of dot-separated scopes, most specific
 * last - e.g. `["source.kotlin", "meta.function", "entity.name.function"]`.
 * The convention across grammars is that the prefix carries the meaning, so a
 * longest-prefix match against the table below is enough to theme any of the
 * 229 bundled grammars without knowing which one produced the token.
 *
 * This table is the single source for that mapping; nothing else may translate
 * a scope to a color.
 */
internal object ScopeRules {

    /**
     * Longest matching prefix wins, which is why `keyword.operator` can differ
     * from `keyword` without the order of this list mattering.
     */
    private val RULES: List<Pair<String, SyntaxRole>> = listOf(
        "comment" to SyntaxRole.COMMENT,
        "punctuation.definition.comment" to SyntaxRole.COMMENT,

        "string" to SyntaxRole.STRING,
        "string.regexp" to SyntaxRole.REGEXP,
        "constant.character.escape" to SyntaxRole.ESCAPE,
        "constant.regexp" to SyntaxRole.REGEXP,

        "constant.numeric" to SyntaxRole.NUMBER,
        "constant" to SyntaxRole.CONSTANT,
        "constant.language" to SyntaxRole.CONSTANT,
        "support.constant" to SyntaxRole.CONSTANT,
        "variable.other.constant" to SyntaxRole.CONSTANT,

        "keyword" to SyntaxRole.KEYWORD,
        "keyword.operator" to SyntaxRole.OPERATOR,
        "storage" to SyntaxRole.KEYWORD,
        "storage.type" to SyntaxRole.TYPE,
        "storage.modifier" to SyntaxRole.KEYWORD,

        "entity.name.type" to SyntaxRole.TYPE,
        "entity.name.class" to SyntaxRole.TYPE,
        "entity.name.struct" to SyntaxRole.TYPE,
        "entity.name.enum" to SyntaxRole.TYPE,
        "entity.other.inherited-class" to SyntaxRole.TYPE,
        "support.type" to SyntaxRole.TYPE,
        "support.class" to SyntaxRole.TYPE,

        "entity.name.function" to SyntaxRole.FUNCTION,
        "entity.name.method" to SyntaxRole.FUNCTION,
        "support.function" to SyntaxRole.FUNCTION,
        "meta.function-call" to SyntaxRole.FUNCTION,
        "variable.function" to SyntaxRole.FUNCTION,

        "entity.name.namespace" to SyntaxRole.NAMESPACE,
        "entity.name.module" to SyntaxRole.NAMESPACE,
        "entity.name.scope-resolution" to SyntaxRole.NAMESPACE,

        "entity.name.tag" to SyntaxRole.TAG,
        "entity.other.attribute-name" to SyntaxRole.ATTRIBUTE,

        "variable" to SyntaxRole.VARIABLE,
        "variable.parameter" to SyntaxRole.PARAMETER,
        "variable.other.property" to SyntaxRole.PROPERTY,
        "variable.other.member" to SyntaxRole.PROPERTY,
        "support.variable.property" to SyntaxRole.PROPERTY,
        "meta.object-literal.key" to SyntaxRole.PROPERTY,

        "punctuation" to SyntaxRole.PUNCTUATION,
        "meta.brace" to SyntaxRole.PUNCTUATION,

        "markup.heading" to SyntaxRole.HEADING,
        "entity.name.section" to SyntaxRole.HEADING,
        "markup.underline.link" to SyntaxRole.LINK,
        "string.other.link" to SyntaxRole.LINK,

        "invalid" to SyntaxRole.INVALID,
    )

    /**
     * The scope prefixes that resolve to [role]. Theme `tokenColors` selectors are
     * matched against these so a theme colours roles through this same table
     * (docs/extension-sdk/lld/customization.md sec 8.2) instead of a second one.
     */
    fun prefixesFor(role: SyntaxRole): List<String> = PREFIXES_BY_ROLE[role].orEmpty()

    private val PREFIXES_BY_ROLE: Map<SyntaxRole, List<String>> =
        RULES.groupBy(keySelector = { it.second }, valueTransform = { it.first })

    /** Prefix -> role, sorted longest-first so the first hit is the most specific. */
    private val SORTED = RULES.sortedByDescending { it.first.length }

    /**
     * Resolving walks the token's scope stack from most specific to least, so an
     * `entity.name.function` inside a `meta.function` wins over the container.
     */
    fun roleFor(scopes: List<String>): SyntaxRole {
        for (i in scopes.indices.reversed()) {
            val role = roleForScope(scopes[i])
            if (role != SyntaxRole.PLAIN) return role
        }
        return SyntaxRole.PLAIN
    }

    /**
     * Grammars reuse a small vocabulary of scope strings across millions of
     * tokens, so each one is matched against [SORTED] once and remembered.
     * [SyntaxRole.PLAIN] doubles as "no rule matched". Only called under
     * [TextMateHighlighter]'s lock, so a plain map is enough.
     */
    private val cache = HashMap<String, SyntaxRole>()

    private fun roleForScope(scope: String): SyntaxRole = cache.getOrPut(scope) {
        SORTED.firstOrNull { (prefix, _) ->
            scope.startsWith(prefix) && (scope.length == prefix.length || scope[prefix.length] == '.')
        }?.second ?: SyntaxRole.PLAIN
    }
}
