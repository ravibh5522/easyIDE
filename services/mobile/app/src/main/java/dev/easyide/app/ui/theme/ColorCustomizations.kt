package dev.easyide.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The user's colour customizations (customization.md 8.3), as the three setting values:
 * `workbench.colorCustomizations`, `editor.tokenColorCustomizations` and
 * `editor.semanticTokenColorCustomizations`. Each may hold `"[<theme label>]"` blocks that
 * apply only while that theme is active, on top of the top-level entries.
 */
@Immutable
data class ColorCustomizations(
    val colors: JsonObject = EMPTY_OBJECT,
    val tokenColors: JsonObject = EMPTY_OBJECT,
    val semanticTokenColors: JsonObject = EMPTY_OBJECT,
) {
    val isEmpty: Boolean get() = colors.isEmpty() && tokenColors.isEmpty() && semanticTokenColors.isEmpty()

    companion object {
        private val EMPTY_OBJECT = JsonObject(emptyMap())
        val NONE = ColorCustomizations()
    }
}

/** A customized theme plus the entries that were skipped (bad colour, unknown key), as `section:key`. */
data class CustomizedTheme(val tokens: ThemeTokens, val invalidEntries: List<String>)

/**
 * Lays [ColorCustomizations] over the active theme's [ThemeTokens] in the merge order of
 * customization.md 8.3 (later wins):
 *
 * - UI colours: theme < top-level `workbench.colorCustomizations` < its `[label]` block; ids go
 *   through [ThemeColorMap] like theme `colors`.
 * - Syntax roles: theme < `textMateRules` (collapsed onto roles like `tokenColors`) < `roles`
 *   (easyIDE role names) and VS Code's shorthands (`comments`, `keywords` ...) < the same inside
 *   `[label]`. `fontStyle` (bold / italic / underline) is kept per role in [SyntaxColors.styles].
 * - Semantic: theme `semanticTokenColors` < `rules` < `[label].rules`; `enabled` (top-level, then
 *   `[label]`) overrides the theme's `semanticHighlighting` flag.
 *
 * Colours accept `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`; anything else is skipped and listed.
 * Pure; memoise by input in the caller.
 */
object ThemeCustomizer {

    /** VS Code's `editor.tokenColorCustomizations` shorthand keys. */
    private val SHORTHANDS: Map<String, SyntaxRole> = mapOf(
        "comments" to SyntaxRole.COMMENT,
        "strings" to SyntaxRole.STRING,
        "keywords" to SyntaxRole.KEYWORD,
        "numbers" to SyntaxRole.NUMBER,
        "types" to SyntaxRole.TYPE,
        "functions" to SyntaxRole.FUNCTION,
        "variables" to SyntaxRole.VARIABLE,
    )

    private val ROLE_BY_NAME: Map<String, SyntaxRole> = SyntaxRole.entries.associateBy { it.name.lowercase() }

    fun apply(tokens: ThemeTokens, custom: ColorCustomizations, themeLabel: String?): CustomizedTheme {
        if (custom.isEmpty) return CustomizedTheme(tokens, emptyList())
        val invalid = ArrayList<String>()
        val colors = LinkedHashMap<ColorToken, Color>()
        for (section in sections(custom.colors, themeLabel)) {
            // Table order decides between ids feeding one token, as for theme files.
            for ((key, token) in ThemeColorMap.BY_VSCODE_KEY) {
                val raw = section[key] ?: continue
                val color = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parseHexColor)
                if (color == null) invalid += "workbench.colorCustomizations:$key" else colors[token] = color
            }
        }
        val roles = LinkedHashMap<SyntaxRole, TokenStyle>()
        for (section in sections(custom.tokenColors, themeLabel)) syntaxSection(section, roles, invalid)
        val semanticRules = LinkedHashMap(tokens.semantic.rules)
        var enabled = tokens.semantic.highlighting
        for (section in sections(custom.semanticTokenColors, themeLabel)) {
            ((section["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)?.let { enabled = it }
            val rules = section["rules"] as? JsonObject ?: continue
            for ((selector, value) in rules) {
                val style = styleOf(value)
                if (style == null || SemanticSelector.parse(selector) == null) {
                    invalid += "editor.semanticTokenColorCustomizations:$selector"
                    continue
                }
                semanticRules.remove(selector)
                semanticRules[selector] = style
            }
        }
        val syntax = if (roles.isEmpty()) tokens.syntax else withRoles(tokens.syntax, roles)
        val semantic = SemanticTokenColors(semanticRules, enabled)
        return CustomizedTheme(tokens.withOverrides(colors, syntax, semantic), invalid)
    }

    /** The top-level entries, then the active theme's `[label]` block. */
    private fun sections(root: JsonObject, themeLabel: String?): List<JsonObject> {
        val top = JsonObject(root.filterKeys { !isThemeBlock(it) })
        val block = themeLabel?.takeIf { it.isNotEmpty() }?.let { root["[$it]"] as? JsonObject }
        return listOfNotNull(top, block)
    }

    private fun isThemeBlock(key: String) = key.length > 2 && key.startsWith("[") && key.endsWith("]")

    private fun syntaxSection(section: JsonObject, roles: MutableMap<SyntaxRole, TokenStyle>, invalid: MutableList<String>) {
        (section["textMateRules"] as? JsonArray)?.let { array ->
            val rules = array.mapIndexedNotNull { index, entry ->
                val o = entry as? JsonObject
                val style = (o?.get("settings") as? JsonObject)?.let(::styleOf)
                if (o == null || style == null) {
                    invalid += "editor.tokenColorCustomizations:textMateRules[$index]"
                    return@mapIndexedNotNull null
                }
                scopesOf(o["scope"]) to style
            }
            // Per property, as TextMate themes resolve: the best rule with a colour gives the
            // colour, the best rule with a font style gives the font style.
            val colors = VsCodeThemeMapper.collapse(rules.filter { it.second.color != null })
            val fonts = VsCodeThemeMapper.collapse(rules.map { it.first to it.second.copy(color = null) }.filter { !it.second.isEmpty })
            for (role in colors.keys + fonts.keys) {
                val style = (colors[role] ?: TokenStyle.NONE).copy(bold = null, italic = null, underline = null).mergedWith(fonts[role] ?: TokenStyle.NONE)
                roles[role] = roles[role]?.mergedWith(style) ?: style
            }
        }
        val named = buildList {
            SHORTHANDS.forEach { (key, role) -> section[key]?.let { add(Triple(key, role, it)) } }
            (section["roles"] as? JsonObject)?.forEach { (name, value) ->
                val role = ROLE_BY_NAME[name.lowercase()]
                if (role == null) invalid += "editor.tokenColorCustomizations:roles.$name" else add(Triple("roles.$name", role, value))
            }
        }
        for ((key, role, value) in named) {
            val style = styleOf(value)
            if (style == null) invalid += "editor.tokenColorCustomizations:$key" else roles[role] = roles[role]?.mergedWith(style) ?: style
        }
    }

    /** `scope` is a string (maybe comma-separated), an array of strings, or absent (the global rule). */
    private fun scopesOf(e: JsonElement?): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }

    /**
     * A colour string, or `{foreground?, fontStyle?, bold?, italic?, underline?}`; null when
     * nothing valid is in it (a bad colour invalidates the entry).
     */
    internal fun styleOf(e: JsonElement): TokenStyle? {
        if (e is JsonPrimitive) return if (e.isString) parseHexColor(e.content)?.let { TokenStyle(it) } else null
        val o = e as? JsonObject ?: return null
        val fg = o["foreground"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::parseHexColor) ?: return null }
        val fontStyle = (o["fontStyle"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val style = TokenStyle.fromFontStyle(fontStyle, fg).mergedWith(TokenStyle(bold = flag("bold"), italic = flag("italic"), underline = flag("underline")))
        return style.takeIf { !it.isEmpty }
    }

    private fun withRoles(base: SyntaxColors, roles: Map<SyntaxRole, TokenStyle>): SyntaxColors {
        fun c(role: SyntaxRole) = roles[role]?.color ?: base[role]
        val styles = LinkedHashMap(base.styles)
        for ((role, style) in roles) {
            val fontOnly = style.copy(color = null)
            if (!fontOnly.isEmpty) styles[role] = styles[role]?.mergedWith(fontOnly) ?: fontOnly
        }
        return SyntaxColors(
            plain = c(SyntaxRole.PLAIN), keyword = c(SyntaxRole.KEYWORD), string = c(SyntaxRole.STRING),
            comment = c(SyntaxRole.COMMENT), number = c(SyntaxRole.NUMBER), type = c(SyntaxRole.TYPE),
            function = c(SyntaxRole.FUNCTION), variable = c(SyntaxRole.VARIABLE), parameter = c(SyntaxRole.PARAMETER),
            property = c(SyntaxRole.PROPERTY), constant = c(SyntaxRole.CONSTANT), operator = c(SyntaxRole.OPERATOR),
            punctuation = c(SyntaxRole.PUNCTUATION), tag = c(SyntaxRole.TAG), attribute = c(SyntaxRole.ATTRIBUTE),
            namespace = c(SyntaxRole.NAMESPACE), regexp = c(SyntaxRole.REGEXP), escape = c(SyntaxRole.ESCAPE),
            heading = c(SyntaxRole.HEADING), link = c(SyntaxRole.LINK), invalid = c(SyntaxRole.INVALID),
            styles = styles,
        )
    }
}
