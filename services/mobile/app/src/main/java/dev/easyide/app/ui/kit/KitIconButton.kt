package dev.easyide.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/**
 * A glyph-only action. [contentDescription] is required and has no default: an icon button
 * without a name is unusable with a screen reader. The drawn hover square is the toolbar button
 * size (28dp dense), the layout box the hit box token and the touch region the touch floor.
 */
@Composable
fun KitIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
) {
    val colors = Kit.colors
    val tint = if (enabled) tone.content(colors) else colors.textDisabled
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val shape = RoundedCornerShape(Kit.radius.s)

    Box(
        modifier = modifier
            .kitTag("icon-button")
            .kitHitSlop()
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Kit.control.toolbarButton)
                .clip(shape)
                .kitStateLayer(flags, enabled, tint)
                .kitFocusRing(flags.focused, shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, contentDescription, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(tint))
        }
    }
}
