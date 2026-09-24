package dev.easyide.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.Role

/**
 * Makes a surface tappable with the kit's states (kit.md 3.1): pressed is the overlay tone, hover
 * (pointer) a neutral wash, keyboard focus a 2dp accent ring drawn over the content, inset so a
 * clipping parent never trims it. States are read while drawing, so a press does not recompose.
 * Ripple is off on purpose: the tone step is the feedback (identity: no stock widgets).
 */
@Composable
internal fun Modifier.kitPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    shape: Shape = RectangleShape,
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
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
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
