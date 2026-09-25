package dev.easyide.app.ui.shell.ext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitGroupSeparator
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.ViewUiState

/** The `gap` and `padding` names of the schema, as the kit's spacing steps. */
@Composable
internal fun spaceOf(name: String?): Dp = when (name) {
    "xs" -> Kit.space.xs
    "s" -> Kit.space.s
    "m" -> Kit.space.m
    "l" -> Kit.space.l
    else -> Kit.space.none
}

private fun horizontalOf(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

private fun verticalOf(align: String?): Alignment.Vertical = when (align) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.Top
}

/** The share of free space a child takes: its own `weight`, else an equal share when it holds a filling body. */
private fun weightOf(n: PlanNode): Float? = n.nums["weight"]?.toFloat() ?: ExtViewTokens.DEFAULT_WEIGHT.takeIf { n.fills }

@Composable
internal fun ColumnNode(n: PlanNode, modifier: Modifier, bounded: Boolean, scroll: Boolean) {
    val gap = spaceOf(n.text["gap"])
    val pad = spaceOf(n.text["padding"])
    val shared = modifier.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(pad)
    // A weight under a scroll has no finite space to share, so children of a scrolling column are simply stacked.
    val weights = bounded && !scroll
    Column(shared, Arrangement.spacedBy(gap), horizontalOf(n.text["align"])) {
        n.children.forEach { child ->
            key(child.key) { ColumnChild(child, weights, bounded && !scroll) }
        }
    }
}

@Composable
private fun ColumnScope.ColumnChild(child: PlanNode, weights: Boolean, bounded: Boolean) {
    val w = if (weights) weightOf(child) else null
    PlanNodeView(child, if (w != null) Modifier.weight(w) else Modifier, bounded)
}

@Composable
internal fun RowNode(n: PlanNode, modifier: Modifier, bounded: Boolean) {
    val ctx = LocalViewCtx.current
    val action = n.action
    val tap = if (action != null) Modifier.kitPressable({ ctx.fire(action, emptyMap()) }) else Modifier
    val alignment = n.text["align"]
    val arrangement = if (alignment == "spread") Arrangement.SpaceBetween else Arrangement.spacedBy(spaceOf(n.text["gap"]))
    Row(
        modifier.fillMaxWidth().then(tap).kitGroupSeparator(Kit.space.l).padding(spaceOf(n.text["padding"])).semantics(mergeDescendants = action != null) {},
        arrangement,
        if (alignment == "spread") Alignment.CenterVertically else verticalOf(alignment),
    ) {
        n.children.forEach { child -> key(child.key) { RowChild(child, bounded) } }
    }
}

@Composable
private fun RowScope.RowChild(child: PlanNode, bounded: Boolean) {
    val w = child.nums["weight"]?.toFloat()
    PlanNodeView(child, if (w != null) Modifier.weight(w) else Modifier, bounded)
}

@Composable
internal fun SectionNode(n: PlanNode, modifier: Modifier) {
    KitSection(n.text["title"], modifier) { n.children.forEach { child -> key(child.key) { PlanNodeView(child) } } }
}

@Composable
internal fun GroupNode(n: PlanNode, modifier: Modifier) {
    KitGroup(modifier.padding(PaddingValues(horizontal = Kit.space.l))) { n.children.forEach { child -> key(child.key) { PlanNodeView(child) } } }
}

/**
 * A tab strip and the selected tab's content. The plan holds only that tab, so nothing else is composed; which tab is
 * showing is the bound value, else the view's own UI state by node key.
 */
@Composable
internal fun TabsNode(n: PlanNode, modifier: Modifier, bounded: Boolean) {
    val ctx = LocalViewCtx.current
    val titles = n.text["titles"].orEmpty().split(TITLE_SEPARATOR)
    val selected = n.nums["selected"]?.toInt() ?: 0
    val select: (Int) -> Unit = { i ->
        val bind = n.bind
        if (bind != null) ctx.commit(bind, kotlinx.serialization.json.JsonPrimitive(i))
        else ctx.setUi(ViewUiState(ctx.ui.expanded, ctx.ui.tabs + (n.key to i)))
    }
    Column(modifier) {
        KitTabs(titles, selected, select, Modifier.fillMaxWidth())
        Box(if (bounded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()) {
            n.children.forEach { tab -> key(tab.key) { PlanNodeView(tab, Modifier.fillMaxSize(), bounded, scroll = !tab.fills && bounded) } }
        }
    }
}

/** The character `ViewPlan` joins tab titles with; a title cannot contain it. */
private const val TITLE_SEPARATOR = '\u0001'

/** Two panes along the axis, sharing the space by [ratio]: the first takes `ratio`, the second what is left. */
@Composable
internal fun SplitNode(n: PlanNode, modifier: Modifier, bounded: Boolean) {
    val ratio = (n.nums["ratio"]?.toFloat() ?: HALF).coerceIn(MIN_RATIO, MAX_RATIO)
    val first = n.children.getOrNull(0)
    val second = n.children.getOrNull(1)
    if (n.text["axis"] == "column") {
        Column(modifier) {
            first?.let { key(it.key) { PlanNodeView(it, Modifier.weight(ratio).fillMaxWidth(), bounded) } }
            second?.let { key(it.key) { PlanNodeView(it, Modifier.weight(1f - ratio).fillMaxWidth(), bounded) } }
        }
    } else {
        Row(modifier) {
            first?.let { key(it.key) { PlanNodeView(it, Modifier.weight(ratio).fillMaxSize(), bounded) } }
            second?.let { key(it.key) { PlanNodeView(it, Modifier.weight(1f - ratio).fillMaxSize(), bounded) } }
        }
    }
}

private const val HALF = 0.5f
private const val MIN_RATIO = 0.1f
private const val MAX_RATIO = 0.9f
