package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.style.TextOverflow

private const val DESCRIPTION_LINES_SECOND = 2

/**
 * The one list row, VS Code anatomy (density.md 1): one line of
 * `[twistie 16][icon slot 20] title  description ......... [trailing][actions]`. The description
 * is muted, inline and gives way to the title; the trailing slot (a value, a badge) and the
 * [actions] are right-aligned, so titles align down a list and no column takes its width from
 * its content. [level] indents a sub row by the tree indent token; [twistie] adds the disclosure
 * column (null: none). [secondLine] moves the description under the title, honoured only in
 * Comfortable or Spacious density or on a [selected] row. Tappable when [onClick] is set;
 * [selected] draws the block marker at the start edge. Inside a [KitGroup] it draws its own
 * separator, inset to the text edge. [mono] sets the title in the chrome monospace for paths and
 * ids; [id] becomes the test tag. [onLongClick] and [onDoubleClick] add a menu and a "keep" beside the tap. Rows are [Kit.control] `rowHeight` tall, never less.
 */
@Composable
fun KitRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
    id: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    level: Int = 0,
    twistie: Twistie? = null,
    secondLine: Boolean = false,
) {
    val colors = Kit.colors
    val space = Kit.space
    val control = Kit.control
    val lines = rowLines(Kit.metrics.density, subtitle != null, secondLine, selected)
    val textEdge = rowTextEdge(control.hPad, control.indent, level, twistie != null, leading != null, space.xs)
    val titleStyle = (if (mono) Kit.text.mono else Kit.text.title).copy(color = if (enabled) colors.plainText else colors.textDisabled)
    val noteStyle = Kit.text.caption.copy(color = if (enabled) colors.textMuted else colors.textDisabled)
    val tappable = if (onClick != null) Modifier.kitPressable(onClick, enabled, Role.Button, onLongClick = onLongClick, onDoubleClick = onDoubleClick) else Modifier

    Row(
        modifier
            .fillMaxWidth()
            .then(if (id != null) Modifier.kitTag(id) else Modifier)
            .then(tappable)
            .kitGroupSeparator(textEdge)
            .kitMarker(selected)
            .semantics(mergeDescendants = true) { if (selected) this.selected = true }
            .defaultMinSize(minHeight = control.rowHeight)
            .padding(start = control.hPad + control.indent * level, end = control.hPad, top = space.xxs, bottom = space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        if (twistie != null) TwistieSlot(twistie, colors.textMuted)
        if (leading != null) Box(Modifier.size(KitSizes.leadingSlot), Alignment.Center) { leading() }
        if (lines == RowLines.Two) {
            Column(Modifier.weight(1f)) {
                BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(subtitle.orEmpty(), style = noteStyle, maxLines = DESCRIPTION_LINES_SECOND, overflow = TextOverflow.Ellipsis)
            }
        } else {
            TitleAndDescription(
                space.s,
                Modifier.weight(1f),
                title = { BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                description = { if (subtitle != null) BasicText(subtitle, style = noteStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        if (trailing != null) Box(Modifier.kitClampHeight(control.rowHeight)) { trailing() }
        if (actions != null) Row(Modifier.kitClampHeight(control.rowHeight), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
