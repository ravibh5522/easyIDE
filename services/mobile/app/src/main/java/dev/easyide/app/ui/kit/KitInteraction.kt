package dev.easyide.app.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.Role

/**
 * Makes a surface tappable with the kit's states (kit.md 3.1): pressed is the overlay tone, hover
 * (pointer) a neutral wash, keyboard focus a 2dp accent ring drawn over the content, inset so a
 * clipping parent never trims it. States are read while drawing, so a press does not recompose.
 * Ripple is off on purpose: the tone step is the feedback (identity: no stock widgets). A [onLongClick] adds the long press
 * (a menu of the pressed thing) and [onDoubleClick] the double tap (keep a preview open) beside the tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.kitPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    shape: Shape = RectangleShape,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
): Modifier {
    val colors = Kit.colors
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    val hovered = source.collectIsHoveredAsState()
    val focused = source.collectIsFocusedAsState()
    val hoverFill = Tone.Neutral.container(colors)
    val ring = Kit.marker
    return this
        .drawBehind {
            if (pressed.value) drawRect(colors.overlay) else if (hovered.value) drawRect(hoverFill)
        }
        .hoverable(source, enabled)
        .then(
            if (onLongClick == null && onDoubleClick == null) {
                Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
            } else {
                Modifier.combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
            },
        )
        .drawWithContent {
            drawContent()
            if (!focused.value) return@drawWithContent
            val w = ring.toPx()
            inset(w / 2) {
                drawOutline(shape.createOutline(size, layoutDirection, this), colors.focus, style = Stroke(w))
            }
        }
}

/** The solid block at the left edge of a selected row: where you are, in the accent (identity.md 2.1). */
@Composable
internal fun Modifier.kitMarker(selected: Boolean, color: Color = Kit.colors.accent): Modifier {
    if (!selected) return this
    val width = Kit.marker
    return drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height)) }
}

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
