package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.icons.iconFor

/**
 * A page frame: title bar (back, title, actions), an optional banner, and the content column
 * capped at [maxContentWidth] and centred on wide windows. The bar and content stay inside the
 * system insets; [content] receives the bottom inset (navigation bar, keyboard) as padding.
 * Separation is a hairline under the bar, not a shadow.
 */
@Composable
fun KitScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
    maxContentWidth: Dp = Kit.contentMax,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = Kit.colors
    val space = Kit.space
    val hairline = Kit.hairline
    val barHeight = rowMinHeight(Kit.control, WidthClass.COMPACT, Kit.metrics.touchFloor)
    val sides = WindowInsetsSides.Horizontal
    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.panel)
                .drawBehind { drawRect(colors.panelBorder, Offset(0f, size.height - hairline.toPx()), Size(size.width, hairline.toPx())) }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Top))
                .defaultMinSize(minHeight = barHeight)
                .padding(horizontal = space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            if (onBack != null) {
                Box(
                    Modifier.kitTouchFloor().kitTag("scaffold-back").kitPressable(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(iconFor("back"), stringResource(R.string.kit_back), tint = colors.plainText)
                }
            }
            BasicText(
                title,
                Modifier.weight(1f).padding(start = if (onBack == null) space.m else space.none).semantics { heading() },
                style = Kit.type.headlineMedium.copy(color = colors.plainText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        if (banner != null) banner()
        Box(
            Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(sides)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
                content(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues())
            }
        }
    }
}
