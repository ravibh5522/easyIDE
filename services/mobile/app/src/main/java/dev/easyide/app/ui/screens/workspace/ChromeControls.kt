package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors

/**
 * Dense controls for editor chrome (source control today). Material's text
 * field and filled button are sized and coloured for forms on a phone; inside
 * a 280dp side panel they dominate the pane. These keep the same behaviour on
 * the workspace tokens: raised surface, hairline border, accent only on focus
 * and on the one primary action.
 */
@Composable
fun DenseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val colors = editorColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.small
    val textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.plainText)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.cursor),
        singleLine = maxLines == 1,
        maxLines = maxLines,
        interactionSource = interaction,
        modifier = modifier.defaultMinSize(minHeight = ControlSize.headerAction),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.raised)
                    .border(Stroke.hairline, if (focused) colors.focus else colors.panelBorder, shape)
                    .padding(horizontal = Spacing.s, vertical = Spacing.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = colors.textMuted))
                inner()
            }
        },
    )
}

/** [PRIMARY] is the pane's one accent-filled action; [GHOST] is text on the panel. */
enum class ChromeButtonStyle { PRIMARY, GHOST }

@Composable
fun ChromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ChromeButtonStyle = ChromeButtonStyle.GHOST,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = editorColors
    val primary = style == ChromeButtonStyle.PRIMARY
    val content = when {
        !enabled -> colors.textDisabled
        primary -> colors.onAccent
        else -> colors.accent
    }
    val container = when {
        !primary -> null
        enabled -> colors.accent
        else -> colors.raised
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = ControlSize.headerAction)
            .clip(MaterialTheme.shapes.small)
            .then(if (container != null) Modifier.background(container) else Modifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(IconSize.s)) }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
