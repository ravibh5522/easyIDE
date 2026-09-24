package dev.easyide.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.IconSize

internal data class TagPaint(val fill: Color, val content: Color, val border: Color)

/** Resting: tone wash with a hairline of the tone. Selected: filled with the tone. Neutral keeps the chrome hairline. */
internal fun tagPaint(tone: Tone, selected: Boolean, colors: EditorColors): TagPaint {
    val content = tone.content(colors)
    val line = if (tone == Tone.Neutral) colors.panelBorder else content
    return if (selected) {
        TagPaint(content, tone.onFill(colors), content)
    } else {
        TagPaint(tone.container(colors), content, line)
    }
}

/**
 * A short mono label: a status, a version, an option. With [onClick] it is a toggle chip and its
 * hit box grows to the touch floor while the drawn chip stays [KitSizes.tag] tall.
 */
@Composable
fun KitTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val paint = tagPaint(tone, selected, Kit.colors)
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val shape = RoundedCornerShape(Kit.metrics.radiusFor(Kit.radius.xs, KitSizes.tag))
    val hit = if (onClick == null) modifier else {
        modifier.kitTouchFloor().clickable(interaction, null, role = Role.Button, onClick = onClick).semantics { this.selected = selected }
    }

    Box(hit.kitTag("tag"), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = KitSizes.tag)
                .clip(shape)
                .background(paint.fill)
                .border(Kit.hairline, paint.border, shape)
                .kitStateLayer(flags, onClick != null, paint.content)
                .kitFocusRing(flags.focused, shape)
                .padding(horizontal = Kit.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
        ) {
            icon?.let { Image(it, null, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(paint.content)) }
            BasicText(text, style = Kit.type.labelSmall.kitMono().copy(color = paint.content), maxLines = 1)
        }
    }
}
