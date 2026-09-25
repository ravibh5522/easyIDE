package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.kitMarker
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.kit.TitleAndDescription
import dev.easyide.sandbox.git.GitRef

/** What a graph row needs besides the row itself: the refs on it, whether it is HEAD or selected, and what tapping it does. */
internal class GraphRowActions(
    val refsOf: (String) -> List<GitRef>,
    val selected: String?,
    val onSelect: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val menuFor: @Composable (GraphRow, List<GitRef>) -> List<KitMenuItem>,
)

/**
 * The commit graph: a drawn lane gutter beside the commit list, one [Canvas][LaneGutter] per row so the
 * whole thing stays inside the caller's `LazyColumn` - a history of thousands of commits costs only the
 * rows on screen, and the lane maths in [CommitGraph] is already per-row.
 */
internal fun LazyListScope.commitGraph(rows: List<GraphRow>, actions: GraphRowActions) {
    items(rows.size, key = { rows[it].commit.id }) { index ->
        val row = rows[index]
        CommitRow(row, actions.refsOf(row.commit.id), row.commit.id == actions.selected, actions)
    }
}

/**
 * One commit on one line, as VS Code draws it: the lane gutter, the subject, the author muted after it, and at
 * the end the ref chips. A tap opens the commit, and selects the row, which reveals the menu button; a long
 * press or right click opens the same menu under the press.
 */
@Composable
private fun CommitRow(row: GraphRow, refs: List<GitRef>, selected: Boolean, actions: GraphRowActions) {
    val colors = Kit.colors
    val laneWidth = Kit.control.indent
    // Like VS Code, the subject starts right after the lanes this row actually uses, not after the graph's
    // widest row, so a commit on the main line keeps the whole width for its text.
    val used = (row.passing + row.incoming + row.parentLanes + row.ending + row.lane).max() + 1
    val drawn = used.coerceIn(1, GitUi.MAX_DRAWN_LANES)
    val press = rememberPressPoint()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    val cd = stringResource(R.string.gitui_commit_row_cd, row.commit.subject, row.commit.authorName)
    val lane = colors.lanes[row.lane % colors.lanes.size]
    val head = refs.any { it.isCurrent }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = Kit.control.rowHeight)
            .hoverable(hover)
            .kitPressPoint(press, onSecondary = { menuAt = it })
            .kitPressable({ actions.onSelect(row.commit.id); actions.onOpen(row.commit.id) }, onLongClick = { menuAt = press.at })
            .kitMarker(selected)
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .padding(start = Kit.control.hPad, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        LaneGutter(row, drawn, colors.lanes, colors.panel, head, laneWidth, Modifier.width(gutterWidth(drawn, laneWidth)).fillMaxHeight())
        SubjectAndChips(
            Modifier.weight(1f),
            subject = {
                TitleAndDescription(
                    Kit.space.s,
                    Modifier,
                    title = { BasicText(row.commit.subject, style = Kit.text.body.copy(color = colors.plainText), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    description = { BasicText(row.commit.authorName, style = Kit.text.caption.copy(color = colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            },
            chips = { RefChips(refs, lane) },
        )
        if (hovered || selected) {
            KitIconButton(iconFor("more_vert"), stringResource(R.string.gitui_commit_actions), { menuAt = press.at })
        }
    }
    if (menuAt != null) KitMenu(true, { menuAt = null }, actions.menuFor(row, refs), at = menuAt)
}

/**
 * The subject and, at the row's end, the ref chips. The chips are measured first but never take more than
 * [GitUi.CHIP_SHARE] of the width, so a commit on three branches still leaves its subject room to be read;
 * the chips ellipsize inside their share.
 */
@Composable
private fun SubjectAndChips(modifier: Modifier, subject: @Composable () -> Unit, chips: @Composable () -> Unit) {
    val gap = Kit.space.s
    Layout({ subject(); chips() }, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val chipMax = (constraints.maxWidth * GitUi.CHIP_SHARE).toInt()
        val loose = constraints.copy(minWidth = 0)
        val c = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = chipMax))
        val roomForSubject = (constraints.maxWidth - if (c != null) c.width + gapPx else 0).coerceAtLeast(0)
        val s = measurables[0].measure(loose.copy(maxWidth = roomForSubject))
        val height = maxOf(s.height, c?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            s.placeRelative(0, (height - s.height) / 2)
            c?.let { it.placeRelative(constraints.maxWidth - it.width, (height - it.height) / 2) }
        }
    }
}
