package dev.easyide.app.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import dev.easyide.app.ui.theme.EasyIdeFonts

/** Pointer and keyboard state of one control, collected once from its interaction source. */
@Immutable
internal class KitFlags(val pressed: Boolean, val hovered: Boolean, val focused: Boolean)

@Composable
internal fun MutableInteractionSource.collectFlags(): KitFlags {
    val pressed by collectIsPressedAsState()
    val hovered by collectIsHoveredAsState()
    val focused by collectIsFocusedAsState()
    return KitFlags(pressed, hovered, focused)
}

/**
 * Hover and press feedback: a wash of [color] over the control. Apply after the clip and
 * background, so the wash follows the corners. No ripple: the identity separates by tone, not motion.
 */
internal fun Modifier.kitStateLayer(flags: KitFlags, enabled: Boolean, color: Color): Modifier {
    val alpha = if (enabled) KitStateLayer.alphaFor(flags.pressed, flags.hovered) else 0f
    return if (alpha == 0f) this else drawBehind { drawRect(color.copy(alpha = alpha)) }
}

/** Visible keyboard focus (U-INT-04): the 2dp ring in the focus colour. */
@Composable
internal fun Modifier.kitFocusRing(focused: Boolean, shape: Shape): Modifier =
    if (focused) border(Kit.marker, Kit.colors.focus, shape) else this

/**
 * The chrome monospace at a text style's size. The typography carries no pairing, so the system
 * pairing (default family) is recognised by its family and gets the platform monospace.
 */
@Composable
internal fun TextStyle.kitMono(): TextStyle {
    val system = Kit.type.bodyMedium.fontFamily == FontFamily.Default
    return copy(fontFamily = if (system) FontFamily.Monospace else EasyIdeFonts.mono)
}
