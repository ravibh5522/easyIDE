package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R

/**
 * An inline message: a tone wash with the 2dp rule of the tone at the left edge, one sentence,
 * at most one action and a dismiss. The rule and the wording carry the tone, not colour alone.
 */
@Composable
fun KitBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    action: KitAction? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = Kit.colors
    val rule = tone.content(colors)
    Row(
        modifier = modifier
            .kitTag("banner")
            .fillMaxWidth()
            .background(tone.container(colors, colors.panel))
            .drawBehind { drawRect(rule, size = Size(Kit.marker.toPx(), size.height)) }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = Kit.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = Kit.type.bodyMedium.copy(color = colors.plainText),
            modifier = Modifier.weight(1f).padding(vertical = Kit.space.s),
        )
        if (action != null) KitButton(action.label, action.onClick, style = KitButtonStyle.Ghost)
        if (onDismiss != null) KitIconButton(Icons.Filled.Close, stringResource(R.string.kitin_dismiss), onDismiss)
    }
}
