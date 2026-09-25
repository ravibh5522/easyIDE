package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where a lane segment starts or ends inside its row: the top edge, the node's centre or the bottom edge. */
internal enum class LaneY { TOP, MID, BOTTOM }

/** One stroke of the gutter: from [fromLane] at [fromY] to [toLane] at [toY], drawn in the colour of [colorLane]. */
internal data class LaneSegment(val colorLane: Int, val fromLane: Int, val fromY: LaneY, val toLane: Int, val toY: LaneY)

/**
 * Every stroke of one row's gutter. A lane entering from above runs straight down, stops at the node when it
 * is the node's own lane, or curves into the node when it ends here (a merge arriving); each parent then leaves
 * the node downward, straight on its first-parent lane and curving to the lane of any other parent.
 * Pure, so the shapes are tested without drawing.
 */
internal fun laneSegments(row: GraphRow): List<LaneSegment> = buildList {
    row.incoming.forEach { lane ->
        when {
            lane == row.lane -> add(LaneSegment(lane, lane, LaneY.TOP, lane, LaneY.MID))
            lane in row.ending -> add(LaneSegment(lane, lane, LaneY.TOP, row.lane, LaneY.MID))
            else -> add(LaneSegment(lane, lane, LaneY.TOP, lane, LaneY.BOTTOM))
        }
    }
    row.parentLanes.forEach { lane -> add(LaneSegment(lane, row.lane, LaneY.MID, lane, LaneY.BOTTOM)) }
}

/** Lanes beyond the drawn width share the last column, so a very wide history stays inside its gutter. */
internal fun foldLane(lane: Int, drawn: Int): Int = lane.coerceAtMost(drawn - 1)

/** The lane-coloured stroke of a segment: straight when it stays in a lane, a smooth S-curve when it changes lane. */
private fun DrawScope.drawSegment(seg: LaneSegment, x: (Int) -> Float, y: (LaneY) -> Float, color: Color, width: Float) {
    val from = Offset(x(seg.fromLane), y(seg.fromY))
    val to = Offset(x(seg.toLane), y(seg.toY))
    val stroke = Stroke(width, cap = StrokeCap.Round)
    if (from.x == to.x) {
        drawLine(color, from, to, width, StrokeCap.Round)
        return
    }
    val midY = (from.y + to.y) / 2f
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x, midY, to.x, midY, to.x, to.y)
    }
    drawPath(path, color, style = stroke)
}

/**
 * The lane gutter of one graph row: the strokes of [laneSegments] in the palette's lane colours, then the
 * round node. A merge commit is a ring with a dot, the checked-out commit a larger node; each has a halo of
 * [halo] colour so it stays readable where lines cross behind it.
 */
@Composable
internal fun LaneGutter(
    row: GraphRow,
    drawn: Int,
    palette: List<Color>,
    halo: Color,
    head: Boolean,
    laneWidth: Dp,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val w = laneWidth.toPx()
        fun x(lane: Int) = w * foldLane(lane, drawn) + w / 2f
        fun y(at: LaneY) = when (at) { LaneY.TOP -> 0f; LaneY.MID -> size.height / 2f; LaneY.BOTTOM -> size.height }
        fun color(lane: Int) = palette[lane % palette.size]
        val width = GitUi.laneStroke.toPx()
        laneSegments(row).forEach { drawSegment(it, ::x, ::y, color(it.colorLane), width) }

        val centre = Offset(x(row.lane), y(LaneY.MID))
        val radius = (if (head) GitUi.headNodeRadius else GitUi.nodeRadius).toPx()
        val fill = color(row.lane)
        drawCircle(halo, radius + GitUi.nodeHalo.toPx(), centre)
        if (row.commit.parents.size > 1) {
            drawCircle(fill, radius, centre, style = Stroke(width))
            drawCircle(fill, radius / 2.5f, centre)
        } else {
            drawCircle(fill, radius, centre)
        }
    }
}

/** The gutter's width: one lane column per drawn lane. */
internal fun gutterWidth(laneCount: Int, laneWidth: Dp): Dp = laneWidth * laneCount.coerceIn(1, GitUi.MAX_DRAWN_LANES)

