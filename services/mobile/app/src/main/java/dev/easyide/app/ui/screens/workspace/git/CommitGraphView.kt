package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.Kit

private val ROW_HEIGHT = 44.dp
private val LANE_WIDTH = 14.dp
private val DOT_RADIUS = 3.5.dp
private const val MAX_DRAWN_LANES = 8

/** Lines merely passing through a row sit slightly behind this row's own dot and connectors. */
private const val PASSING_LANE_ALPHA = 0.85f

/**
 * The commit graph: a drawn lane gutter beside the commit list.
 *
 * Rendered with `Canvas` per row rather than as one tall canvas so the whole
 * thing stays inside the caller's `LazyColumn` - a repository with thousands of
 * commits then costs only the rows on screen, and the lane maths in
 * [CommitGraph] is already per-row.
 */
fun LazyListScope.commitGraph(
    rows: List<GraphRow>,
    onCommitClick: (String) -> Unit,
) {
    items(rows.size, key = { rows[it].commit.id }) { index ->
        CommitRow(rows[index], onCommitClick)
    }
}

@Composable
private fun CommitRow(row: GraphRow, onCommitClick: (String) -> Unit) {
    val colors = Kit.colors
    val laneCount = row.laneCount.coerceIn(1, MAX_DRAWN_LANES)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable { onCommitClick(row.commit.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaneGutter(row, laneCount, colors.lanes, Modifier.width(LANE_WIDTH * laneCount))

        Column(modifier = Modifier.weight(1f).padding(end = Kit.space.s)) {
            BasicText(
                text = row.commit.subject,
                style = Kit.text.caption.copy(color = colors.plainText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = "${row.commit.shortId}  ${row.commit.authorName}",
                style = Kit.text.monoSmall.copy(color = colors.textMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LaneGutter(
    row: GraphRow,
    laneCount: Int,
    palette: List<Color>,
    modifier: Modifier,
) {
    Canvas(modifier = modifier.height(ROW_HEIGHT)) {
        val laneW = LANE_WIDTH.toPx()
        val midY = size.height / 2f
        fun laneX(lane: Int) = laneW * lane + laneW / 2f

        // Lines for every lane still active through this row. Drawn full-height
        // so consecutive rows join without seams.
        row.passing.filter { it < laneCount }.forEach { lane ->
            drawLine(
                color = palette[lane % palette.size].copy(alpha = PASSING_LANE_ALPHA),
                start = Offset(laneX(lane), 0f),
                end = Offset(laneX(lane), size.height),
                strokeWidth = 2f,
            )
        }

        // Connectors from this commit's dot down to each parent's lane. A merge
        // parent in another lane gets an elbow rather than a straight drop.
        row.parentLanes.filter { it < laneCount }.forEach { parentLane ->
            if (parentLane != row.lane) {
                drawLine(
                    color = palette[parentLane % palette.size],
                    start = Offset(laneX(row.lane), midY),
                    end = Offset(laneX(parentLane), size.height),
                    strokeWidth = 2f,
                )
            }
        }

        if (row.lane < laneCount) {
            drawCircle(
                color = palette[row.lane % palette.size],
                radius = DOT_RADIUS.toPx(),
                center = Offset(laneX(row.lane), midY),
            )
        }
    }
}
