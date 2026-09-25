package dev.easyide.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitScaffold

/** How a flow (New project, Install Linux) is presented: a phone gets a page, a wide window a sheet (layout-spec 4). */
enum class FlowPresentation { FullScreen, Sheet }

fun flowPresentation(width: WidthClass): FlowPresentation =
    if (width.isCompact) FlowPresentation.FullScreen else FlowPresentation.Sheet

/**
 * The frame of a flow: full screen with the kit's title bar on compact width, a centred sheet
 * (panel tone, hairline, large radius, close at the top right) on wider windows. [content] scrolls;
 * [footer] is pinned under it, in the thumb zone and above the keyboard, and holds the flow's one
 * primary action. Back and close both call [onBack].
 */
@Composable
fun FlowFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (flowPresentation(LocalWindowSize.current.width) == FlowPresentation.FullScreen) {
        KitScaffold(title, modifier, onBack = onBack) { inset ->
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(bottom = if (footer == null) inset.calculateBottomPadding() else Kit.space.none),
                    content = content,
                )
                if (footer != null) Box(Modifier.padding(inset).padding(Kit.space.l)) { footer() }
            }
        }
        return
    }
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.l)
    Box(
        modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(vertical = Kit.space.xl).widthIn(max = Kit.contentMax).fillMaxWidth()
                .clip(shape).background(colors.panel).border(Kit.hairline, colors.panelBorder, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = Kit.space.l, end = Kit.space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
            ) {
                BasicText(
                    title,
                    Modifier.weight(1f).semantics { heading() },
                    style = Kit.text.display.copy(color = colors.plainText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KitIconButton(Icons.Filled.Close, stringResource(R.string.action_close), onBack)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            if (footer != null) Box(Modifier.padding(Kit.space.l)) { footer() }
        }
    }
}
