package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.currentWindowSize
import dev.easyide.app.ui.props.ControlScale
import dev.easyide.app.ui.theme.EasyIdeFonts

/** Leading icons and marks are 20dp, drawn plain: no tinted circle behind them (U-AI-06). */
private val LEADING_SLOT = 20.dp

private const val SUBTITLE_LINES = 2

/** A row is at least as tall as its width class asks and never below the touch floor. */
internal fun rowMinHeight(control: ControlScale, width: WidthClass, floor: Dp): Dp = maxOf(control.listRow(width), floor)

/**
 * The one list row (kit.md 3.1): leading slot, title, support text, trailing slot. Tappable when
 * [onClick] is set; [selected] draws the block marker at the start edge. Inside a [KitGroup] it
 * draws its own separator, inset to the text edge. [mono] sets the title in the monospace face
 * for paths and ids; [id] becomes the test tag.
 */
@Composable
fun KitRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
    id: String? = null,
) {
    val colors = Kit.colors
    val space = Kit.space
    val minHeight = rowMinHeight(Kit.control, currentWindowSize().width, Kit.metrics.touchFloor)
    val textEdge = space.l + if (leading != null) LEADING_SLOT + space.m else 0.dp
    val titleStyle = Kit.type.titleSmall.let { if (mono) it.copy(fontFamily = EasyIdeFonts.mono, fontWeight = FontWeight.Normal) else it }
    val tappable = if (onClick != null) Modifier.kitPressable(onClick, enabled, Role.Button) else Modifier

    Row(
        modifier
            .fillMaxWidth()
            .then(if (id != null) Modifier.kitTag(id) else Modifier)
            .then(tappable)
            .kitGroupSeparator(textEdge)
            .kitMarker(selected)
            .semantics(mergeDescendants = true) { if (selected) this.selected = true }
            .defaultMinSize(minHeight = minHeight)
            .padding(horizontal = space.l, vertical = space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        if (leading != null) Box(Modifier.size(LEADING_SLOT), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = titleStyle.copy(color = if (enabled) colors.plainText else colors.textDisabled))
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    style = Kit.type.bodySmall.copy(color = if (enabled) colors.textMuted else colors.textDisabled),
                    maxLines = SUBTITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}
