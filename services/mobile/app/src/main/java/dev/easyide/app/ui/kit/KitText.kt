package dev.easyide.app.ui.kit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.FontPairing
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.TypeScale
import dev.easyide.app.ui.theme.TypeSteps

/**
 * The one type set of the interface (density.md, U-DEN-08): a role is a name, a size step, a weight
 * and a line ratio, so the same thing (a row title, a caption, a section header) looks the same on
 * every screen. Colour is the caller's. Read it as `Kit.text.body`; nothing outside `ui/theme` and
 * `ui/kit` names a size or a Material role.
 *
 * The editor and the terminal are not in this set: their font size is a user setting and is laid
 * out independently of the interface density.
 *
 * | role    | dense sp | comfortable sp | weight   | use |
 * |---------|----------|----------------|----------|-----|
 * | display | 20       | 20             | semibold | page title |
 * | heading | 15       | 16             | medium   | dialog title, card title |
 * | title   | 13       | 14             | medium   | row title, button and tab label |
 * | body    | 13       | 14             | regular  | running text, field text |
 * | caption | 12       | 13             | regular  | inline description, support text, status items |
 * | label   | 11       | 12             | medium   | caps section header, tag (tracked) |
 * | mono    | 13       | 14             | regular  | chrome monospace: paths, ids, values |
 * | monoSmall | 12     | 13             | regular  | chrome monospace, inline and dense |
 */
@Immutable
data class KitText(
    val display: TextStyle,
    val heading: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
) {
    val all: List<TextStyle> get() = listOf(display, heading, title, body, caption, label, mono, monoSmall)

    companion object {
        /** Line height as a multiple of the size: at least [MIN_LINE_RATIO], so descenders never clip. */
        const val MIN_LINE_RATIO = 1.15f

        private const val DISPLAY_RATIO = 1.3f
        private const val HEADING_RATIO = 1.33f
        private const val TEXT_RATIO = 1.38f
        private const val CAPTION_RATIO = 1.33f
        private const val LABEL_RATIO = 1.45f

        val DEFAULT = of(Density.COMFORTABLE, FontPairing.GEIST)

        fun of(density: Density, pairing: FontPairing): KitText {
            val steps: TypeSteps = TypeScale.steps(density)
            val sans = EasyIdeFonts.chrome(pairing)
            val mono = EasyIdeFonts.chromeMono(pairing)
            fun role(family: FontFamily, size: TextUnit, weight: FontWeight, ratio: Float, tracking: TextUnit = 0.sp) = TextStyle(
                fontFamily = family,
                fontWeight = weight,
                fontSize = size,
                lineHeight = (size.value * ratio).sp,
                letterSpacing = tracking,
            )
            return KitText(
                display = role(sans, steps.display, FontWeight.SemiBold, DISPLAY_RATIO),
                heading = role(sans, steps.heading, FontWeight.Medium, HEADING_RATIO),
                title = role(sans, steps.body, FontWeight.Medium, TEXT_RATIO),
                body = role(sans, steps.body, FontWeight.Normal, TEXT_RATIO),
                caption = role(sans, steps.caption, FontWeight.Normal, CAPTION_RATIO),
                label = role(sans, steps.label, FontWeight.Medium, LABEL_RATIO, TypeScale.capsTracking),
                mono = role(mono, steps.body, FontWeight.Normal, TEXT_RATIO),
                monoSmall = role(mono, steps.caption, FontWeight.Normal, CAPTION_RATIO),
            )
        }
    }
}

val LocalKitText = staticCompositionLocalOf { KitText.DEFAULT }
