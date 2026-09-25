package dev.easyide.app.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.easyide.app.ui.theme.EditorColors

/**
 * The semantic colour of a component state. The one mapping from tone to theme colours lives
 * here, so a component never picks `colors.error` or `colors.accent` itself and a theme change
 * reaches every tone at once.
 */
enum class Tone { Neutral, Accent, Success, Warning, Danger, Info;

    /** Text, glyph and outline colour of the tone. Neutral is the muted chrome text. */
    fun content(colors: EditorColors): Color = when (this) {
        Neutral -> colors.textMuted
        Accent -> colors.accent
        Success -> colors.success
        Warning -> colors.warning
        Danger -> colors.error
        Info -> colors.info
    }

    /** A wash of the tone over [surface], for tags and banners: 12% of the content colour. */
    fun container(colors: EditorColors, surface: Color = colors.raised): Color =
        content(colors).copy(alpha = WASH_ALPHA).compositeOver(surface)

    /** What is drawn on a solid fill of [content]: the accent has a dedicated on-colour, others use the background. */
    fun onFill(colors: EditorColors): Color = if (this == Accent) colors.onAccent else colors.background

    private companion object {
        const val WASH_ALPHA = 0.12f
    }
}
