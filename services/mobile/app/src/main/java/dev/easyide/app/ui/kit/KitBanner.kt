package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
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
 * An inline message on one compact row: a tone wash with the 2dp rule of the tone at the left edge,
 * one sentence, at most one action and a dismiss. It sits inside the safe area, so a strip at the
 * top of the window never draws under the status bar or a display cutout (U-DEN-07). The rule and the wording carry the tone, not colour alone.
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
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(start = Kit.control.hPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = Kit.text.body.copy(color = colors.plainText),
            modifier = Modifier.weight(1f).padding(vertical = Kit.space.xs),
        )
        if (action != null) KitButton(action.label, action.onClick, style = KitButtonStyle.Ghost)
        if (onDismiss != null) KitIconButton(Icons.Filled.Close, stringResource(R.string.kitin_dismiss), onDismiss)
    }
}
