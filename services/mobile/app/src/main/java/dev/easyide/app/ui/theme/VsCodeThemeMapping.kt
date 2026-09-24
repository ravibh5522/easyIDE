package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.screens.workspace.syntax.ScopeRules

/**
 * The colour sections of a VS Code colour theme file, already read out of its
 * JSON (reading the file is the extension runtime's job; this layer is pure).
 */
data class VsCodeColorTheme(
    /** `colors`: workbench colour id -> `#hex`. */
    val colors: Map<String, String> = emptyMap(),
    /** `tokenColors`, in file order: later rules win ties, as in TextMate. */
    val tokenColors: List<TokenColorRule> = emptyList(),
    /** `semanticTokenColors`: selector (`type.modifier:language`) -> foreground `#hex`. */
    val semanticTokenColors: Map<String, String> = emptyMap(),
)

/**
 * One `tokenColors` entry. [scopes] holds the raw selector strings (`scope` may
 * be a string or an array in JSON, and a string may itself be comma-separated);
 * an empty list is the scope-less global rule that sets the default text colour.
 */
data class TokenColorRule(val scopes: List<String>, val foreground: String?)

/** A theme laid over a base, plus what did not map, for `validate` and the Extension Log. */
data class MappedTheme(
    val tokens: ThemeTokens,
    /** Valid `semanticTokenColors`, for the semantic-token layer that overlays roles (LSP-33). */
    val semanticColors: Map<String, Color>,
    /** `colors` keys with no easyIDE token (EXT-22: listed, not an error). */
    val unmappedKeys: List<String>,
    /** Entries skipped because the value is not a colour, as `section:key`. */
    val invalidEntries: List<String>,
)

/**
 * Maps a VS Code colour theme onto [ThemeTokens] (customization.md sec 8.2).
 *
 * - `colors` go through [ThemeColorMap.BY_VSCODE_KEY]; then [ThemeColorMap.DERIVED]
 *   fills tokens the theme left unset from ones it did set.
 * - `tokenColors` collapse onto [SyntaxRole]s via [ScopeRules.prefixesFor]: a
 *   selector S matches a role prefix P when `P == S` or P starts with `S.`; the
 *   longest matching S wins, ties go to the later rule. Descendant selectors use
 *   their last segment and exclusions (` - ...`) are dropped. Roles no rule
 *   matches keep the base colour; PLAIN follows `editor.foreground` unless a
 *   scope-less rule sets it.
 * - Only foregrounds are applied; `fontStyle` is the ThemeResolver's concern.
 */
object VsCodeThemeMapper {

    fun map(theme: VsCodeColorTheme, base: ThemeTokens): MappedTheme {
        val invalid = mutableListOf<String>()
        val set = mutableMapOf<ColorToken, Color>()
        // Table order, not file order, decides between ids feeding one token.
        for ((key, token) in ThemeColorMap.BY_VSCODE_KEY) {
            val raw = theme.colors[key] ?: continue
            val color = parseHexColor(raw)
            if (color == null) invalid += "colors:$key" else set[token] = color
        }
        for ((target, source) in ThemeColorMap.DERIVED) {
            if (target !in set) set[source]?.let { set[target] = it }
        }
        val unmapped = theme.colors.keys.filter { it !in ThemeColorMap.BY_VSCODE_KEY }.sorted()

        val syntax = mapSyntax(theme.tokenColors, base.syntax, set[ColorToken.EDITOR_FOREGROUND], invalid)
        val semantic = buildMap {
            for ((selector, raw) in theme.semanticTokenColors) {
                val color = parseHexColor(raw)
                if (color == null) invalid += "semanticTokenColors:$selector" else put(selector, color)
            }
        }
        return MappedTheme(base.withOverrides(set, syntax), semantic, unmapped, invalid)
    }

    private fun mapSyntax(
        rules: List<TokenColorRule>,
        base: SyntaxColors,
        editorForeground: Color?,
        invalid: MutableList<String>,
    ): SyntaxColors {
        val parsed = rules.mapIndexedNotNull { index, rule ->
            val raw = rule.foreground ?: return@mapIndexedNotNull null
            val color = parseHexColor(raw)
            if (color == null) {
                invalid += "tokenColors:$index"
                return@mapIndexedNotNull null
            }
            ParsedRule(rule.scopes.flatMap(::selectorsOf), color)
        }
        val plainRule = parsed.lastOrNull { it.selectors.isEmpty() }?.color
        val byRole = SyntaxRole.entries.associateWith { role ->
            if (role == SyntaxRole.PLAIN) plainRule ?: editorForeground ?: base.plain
            else bestMatch(role, parsed) ?: base[role]
        }
        return byRole.toSyntaxColors()
    }

    private class ParsedRule(val selectors: List<String>, val color: Color)

    /**
     * Longest selector matching any of [role]'s prefixes. Rules are walked in file
     * order and `>=` keeps the last of equally long matches, so a later rule wins a tie.
     */
    private fun bestMatch(role: SyntaxRole, rules: List<ParsedRule>): Color? {
        val prefixes = ScopeRules.prefixesFor(role)
        var best: Color? = null
        var bestLength = -1
        for (rule in rules) {
            for (selector in rule.selectors) {
                val hits = prefixes.any { it == selector || it.startsWith("$selector.") }
                if (hits && selector.length >= bestLength) {
                    best = rule.color
                    bestLength = selector.length
                }
            }
        }
        return best
    }

    /** `"a b, c - d"` -> `["b", "c"]`: comma alternatives, last descendant segment, no exclusions. */
    private fun selectorsOf(raw: String): List<String> = raw.split(',').mapNotNull { part ->
        part.substringBefore(" -").trim().split(WHITESPACE).last().takeIf { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Parses `#RGB`, `#RGBA`, `#RRGGBB` or `#RRGGBBAA` (CSS order: alpha last), the
 * forms VS Code theme files use. Anything else is null, so a bad entry is
 * skipped and reported rather than painted as black.
 */
fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (raw.trim().firstOrNull() != '#' || hex.any { it.digitToIntOrNull(HEX_RADIX) == null }) return null
    val full = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val rgba = if (full.length == 6) full + OPAQUE_ALPHA else full
    val value = rgba.toLong(HEX_RADIX)
    val alpha = value and BYTE_MASK
    return Color((alpha shl ALPHA_SHIFT) or (value ushr BYTE_BITS))
}

private const val HEX_RADIX = 16
private const val OPAQUE_ALPHA = "ff"
private const val BYTE_MASK = 0xFFL
private const val BYTE_BITS = 8
private const val ALPHA_SHIFT = 24

private fun Map<SyntaxRole, Color>.toSyntaxColors() = SyntaxColors(
    plain = getValue(SyntaxRole.PLAIN),
    keyword = getValue(SyntaxRole.KEYWORD),
    string = getValue(SyntaxRole.STRING),
    comment = getValue(SyntaxRole.COMMENT),
    number = getValue(SyntaxRole.NUMBER),
    type = getValue(SyntaxRole.TYPE),
    function = getValue(SyntaxRole.FUNCTION),
    variable = getValue(SyntaxRole.VARIABLE),
    parameter = getValue(SyntaxRole.PARAMETER),
    property = getValue(SyntaxRole.PROPERTY),
    constant = getValue(SyntaxRole.CONSTANT),
    operator = getValue(SyntaxRole.OPERATOR),
    punctuation = getValue(SyntaxRole.PUNCTUATION),
    tag = getValue(SyntaxRole.TAG),
    attribute = getValue(SyntaxRole.ATTRIBUTE),
    namespace = getValue(SyntaxRole.NAMESPACE),
    regexp = getValue(SyntaxRole.REGEXP),
    escape = getValue(SyntaxRole.ESCAPE),
    heading = getValue(SyntaxRole.HEADING),
    link = getValue(SyntaxRole.LINK),
    invalid = getValue(SyntaxRole.INVALID),
)
