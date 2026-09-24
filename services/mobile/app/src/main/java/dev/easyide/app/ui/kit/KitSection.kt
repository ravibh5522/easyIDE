package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.easyide.app.R

/**
 * The header row of a section (density.md 1): [Kit.control] `sectionHeaderHeight` tall, a
 * disclosure twistie when [expanded] is set (null: not collapsible, no column), the title in 11sp
 * caps, then a count badge and [actions] at the end. It is its own composable so a long list can
 * pin it with `LazyListScope.stickyHeader`. A tap anywhere on a collapsible header calls [onToggle].
 */
@Composable
fun KitSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
    inset: Dp = Kit.control.hPad,
) {
    val colors = Kit.colors
    val state = when (expanded) {
        null -> null
        true -> stringResource(R.string.kit_section_expanded)
        false -> stringResource(R.string.kit_section_collapsed)
    }
    val tap = if (expanded != null) Modifier.kitPressable(onToggle) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(tap)
            .semantics(mergeDescendants = true) { heading(); if (state != null) stateDescription = state }
            .defaultMinSize(minHeight = Kit.control.sectionHeaderHeight)
            .padding(start = inset, end = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        if (expanded != null) TwistieSlot(if (expanded) Twistie.Expanded else Twistie.Collapsed, colors.textMuted)
        else Box(Modifier.size(KitSizes.twistieSlot), Alignment.Center) { if (Kit.feel.motif.drawsSupporting) PromptGlyph() }
        BasicText(title.uppercase(), Modifier.weight(1f), style = Kit.text.label.copy(color = colors.textMuted), maxLines = 1)
        if (count != null) CountBadge(count)
        if (actions != null) Row(Modifier.kitClampHeight(Kit.control.sectionHeaderHeight), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A rounded neutral chip with a number, as VS Code shows beside a section title. */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Kit.metrics.radiusFor(Kit.radius.m, Kit.control.tagHeight))
    Box(
        modifier
            .defaultMinSize(minWidth = Kit.control.tagHeight, minHeight = Kit.control.tagHeight)
            .clip(shape)
            .background(Tone.Neutral.container(Kit.colors))
            .padding(horizontal = Kit.space.xs),
        Alignment.Center,
    ) {
        BasicText(count.toString(), style = Kit.text.label.copy(color = Kit.colors.textMuted, letterSpacing = TextUnit.Unspecified), maxLines = 1)
    }
}

/**
 * A titled block of a page: a [KitSectionHeader], the [content] in a [KitGroup] and an optional
 * description under it. Owns the page gutter and the space above, so a page is a plain column of
 * sections. A null [title] omits the header. [collapsible] adds the twistie and keeps the open state
 * across rotation (`rememberSaveable`), starting as [startExpanded]. [flat] is the side-panel form:
 * no gutter and no group border, the rows sit directly on the panel (U-DEN-03).
 */
@Composable
fun KitSection(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    count: Int? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    collapsible: Boolean = false,
    startExpanded: Boolean = true,
    flat: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Kit.colors
    val space = Kit.space
    var open by rememberSaveable(title) { mutableStateOf(startExpanded) }
    val expanded = if (collapsible) open else null
    val gutter = if (flat) Modifier else Modifier.padding(start = Kit.control.hPad, end = Kit.control.hPad, top = space.m)
    Column(modifier.fillMaxWidth().then(gutter)) {
        if (title != null) {
            KitSectionHeader(title, count = count, expanded = expanded, onToggle = { open = !open }, actions = actions, inset = if (flat) Kit.control.hPad else space.xs)
        }
        if (expanded == false) return@Column
        if (flat) Column(Modifier.fillMaxWidth(), content = content) else KitGroup(content = content)
        if (description != null) {
            BasicText(
                description,
                Modifier.padding(start = space.xs, top = space.xs),
                style = Kit.text.caption.copy(color = colors.textMuted),
            )
        }
    }
}
