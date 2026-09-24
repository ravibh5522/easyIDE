package dev.easyide.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.easyide.app.ui.screens.workspace.syntax.SemanticKey
import dev.easyide.app.ui.screens.workspace.syntax.SemanticRules
import java.util.concurrent.ConcurrentHashMap

/**
 * A colour plus optional font style (customization.md sec 8.2). Null fields leave whatever is
 * underneath: a style with only `bold` keeps the TextMate colour.
 */
@Immutable
data class TokenStyle(
    val color: Color? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
) {
    val isEmpty: Boolean get() = color == null && bold == null && italic == null && underline == null

    /** Field-wise: [over]'s set fields win. */
    fun mergedWith(over: TokenStyle): TokenStyle = TokenStyle(
        color = over.color ?: color,
        bold = over.bold ?: bold,
        italic = over.italic ?: italic,
        underline = over.underline ?: underline,
    )

    /** Only the set fields, so painting it over another span changes nothing else. */
    fun toSpanStyle(): SpanStyle = SpanStyle(
        color = color ?: Color.Unspecified,
        fontWeight = bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
        fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
        textDecoration = underline?.let { if (it) TextDecoration.Underline else TextDecoration.None },
    )

    companion object {
        val NONE = TokenStyle()

        /**
         * VS Code `fontStyle`: space-separated `bold`, `italic`, `underline`; an empty string
         * clears all three. Unknown words (`strikethrough`) are ignored.
         */
        fun fromFontStyle(fontStyle: String?, color: Color? = null): TokenStyle {
            if (fontStyle == null) return TokenStyle(color)
            val words = fontStyle.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            return TokenStyle(color, bold = "bold" in words, italic = "italic" in words, underline = "underline" in words)
        }
    }
}

/**
 * The active theme's semantic token styling: `semanticTokenColors` merged with
 * `editor.semanticTokenColorCustomizations` (customization.md 8.3), and the theme's own
 * `semanticHighlighting` flag used when `editor.semanticHighlighting.enabled` is
 * `configuredByTheme`.
 *
 * @property rules selector (`type.modifier:language`, `*` for any type) -> style, merge order
 *   kept: a later entry wins a tie.
 * @property highlighting null for "theme did not say"; built-in palettes say true.
 */
@Immutable
data class SemanticTokenColors(
    val rules: Map<String, TokenStyle> = emptyMap(),
    val highlighting: Boolean? = true,
) {
    companion object {
        /** The built-in palettes: no semantic rules (roles via [SemanticRules]), highlighting on. */
        val BUILT_IN = SemanticTokenColors()
    }
}

/** One parsed semantic selector. */
internal data class SemanticSelector(val type: String, val modifiers: Set<String>, val language: String?) {

    /**
     * VS Code's selector priority, or -1 when [key] in [languageId] does not match: a named
     * type 100, each modifier 100, a language 10; the highest wins.
     */
    fun score(key: SemanticKey, languageId: String?): Int {
        if (type != WILDCARD && type != key.type) return -1
        if (!key.modifiers.containsAll(modifiers)) return -1
        if (language != null && language != languageId) return -1
        return (if (type == WILDCARD) 0 else TYPE_SCORE) + modifiers.size * MODIFIER_SCORE + (if (language != null) LANGUAGE_SCORE else 0)
    }

    companion object {
        const val WILDCARD = "*"
        private const val TYPE_SCORE = 100
        private const val MODIFIER_SCORE = 100
        private const val LANGUAGE_SCORE = 10
        private val PART = Regex("[A-Za-z0-9_*-]+")

        /** `type(.modifier)*(:language)?`; null for anything else. */
        fun parse(raw: String): SemanticSelector? {
            val (head, language) = raw.trim().split(':', limit = 2).let { it[0] to it.getOrNull(1)?.trim() }
            val parts = head.split('.')
            if (parts.any { !PART.matches(it) } || (language != null && !PART.matches(language))) return null
            return SemanticSelector(parts[0], parts.drop(1).toSet(), language)
        }
    }
}

/**
 * Resolves a semantic token to the span style painted over TextMate colouring, for one
 * document language: the best theme/customization selector if any matches, else the
 * [SemanticRules] role's colour, else null (keep TextMate). Memoised per key; safe to share
 * across threads.
 */
class SemanticStyler(
    private val syntax: SyntaxColors,
    colors: SemanticTokenColors,
    private val languageId: String?,
) {
    private val selectors: List<Pair<SemanticSelector, TokenStyle>> =
        colors.rules.mapNotNull { (raw, style) -> SemanticSelector.parse(raw)?.let { it to style } }
    private val memo = ConcurrentHashMap<SemanticKey, Any>()

    fun styleFor(key: SemanticKey): SpanStyle? {
        val hit = memo.getOrPut(key) { resolve(key)?.toSpanStyle() ?: NONE }
        return hit as? SpanStyle
    }

    internal fun resolve(key: SemanticKey): TokenStyle? {
        var best: TokenStyle? = null
        var bestScore = -1
        for ((selector, style) in selectors) {
            val score = selector.score(key, languageId)
            if (score >= 0 && score >= bestScore) {
                best = style
                bestScore = score
            }
        }
        val role = SemanticRules.roleFor(key.type, key.modifiers)
        val roleStyle = role?.let { TokenStyle(syntax[it]) }
        return when {
            best == null -> roleStyle
            best.color == null && roleStyle != null -> roleStyle.mergedWith(best)
            else -> best
        }
    }

    private companion object {
        val NONE = Any()
    }
}
