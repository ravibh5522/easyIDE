package dev.easyide.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A complete theme: one colour for every [ColorToken] plus one per [SyntaxRole].
 *
 * This is the single palette object of decision 0019 (ux-overhaul's
 * "EasyIdeColors"): Material's [androidx.compose.material3.ColorScheme] and
 * [EditorColors] are both projections of it ([toColorScheme], [toEditorColors]),
 * so chrome and editor cannot drift apart.
 *
 * Completeness is structural. The only ways to build one are [of], which asks a
 * total function for every token, and [withOverrides], which starts from a
 * complete set - so a missing colour is not a state this type can be in, and
 * reads never need a fallback.
 */
@Immutable
class ThemeTokens private constructor(
    /** Whether the base is dark; picks Material's light/dark defaults and the terminal cursor logic. */
    val isDark: Boolean,
    private val values: List<Color>,
    val syntax: SyntaxColors,
    /** Semantic token rules and the theme's `semanticHighlighting` flag (customization.md 8.3). */
    val semantic: SemanticTokenColors = SemanticTokenColors.BUILT_IN,
) {
    operator fun get(token: ColorToken): Color = values[token.ordinal]

    /** A copy with [overrides] applied on top; tokens not named keep their colour. */
    fun withOverrides(
        overrides: Map<ColorToken, Color>,
        syntax: SyntaxColors = this.syntax,
        semantic: SemanticTokenColors = this.semantic,
    ): ThemeTokens {
        if (overrides.isEmpty() && syntax == this.syntax && semantic == this.semantic) return this
        return ThemeTokens(isDark, ColorToken.entries.map { overrides[it] ?: this[it] }, syntax, semantic)
    }

    override fun equals(other: Any?): Boolean =
        other is ThemeTokens && isDark == other.isDark && values == other.values && syntax == other.syntax && semantic == other.semantic

    override fun hashCode(): Int = ((values.hashCode() * 31 + syntax.hashCode()) * 31 + isDark.hashCode()) * 31 + semantic.hashCode()

    override fun toString(): String = "ThemeTokens(isDark=$isDark)"

    companion object {
        /** Builds a set by asking [colorOf] for every token, in declaration order. */
        fun of(isDark: Boolean, syntax: SyntaxColors, colorOf: (ColorToken) -> Color): ThemeTokens =
            ThemeTokens(isDark, ColorToken.entries.map(colorOf), syntax)
    }
}
