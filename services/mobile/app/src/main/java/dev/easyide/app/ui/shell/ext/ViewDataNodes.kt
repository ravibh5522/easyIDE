package dev.easyide.app.ui.shell.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitGroupSeparator
import dev.easyide.app.ui.kit.kitMono
import dev.easyide.extensions.view.ChatMessage
import dev.easyide.extensions.view.Payload
import dev.easyide.extensions.view.PlanColumn
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.RowSource
import dev.easyide.extensions.view.TreeRow
import dev.easyide.extensions.view.ViewLimits
import dev.easyide.extensions.view.ViewType
import dev.easyide.extensions.view.ViewUiState

@Composable
internal fun DataNode(n: PlanNode, modifier: Modifier) {
    when (n.type) {
        ViewType.LIST -> ListNode(n, modifier)
        ViewType.TREE -> TreeNode(n, modifier)
        ViewType.TABLE -> TableNode(n, modifier)
        ViewType.LOG_STREAM -> LogNode(n, modifier)
        ViewType.CHAT -> ChatNode(n, modifier)
        else -> Unit
    }
}

/** A lazy body draws only the rows on screen; anything else was planned with a capped row count and is composed whole. */
@Composable
private fun <T> Rows(rows: RowSource<T>, lazy: Boolean, modifier: Modifier, row: @Composable (T) -> Unit) {
    if (lazy) {
        LazyColumn(modifier) {
            items(rows.count, key = { rows.key(it) }) { i -> rows.at(i)?.let { row(it) } }
        }
    } else {
        Column(modifier) { for (i in 0 until rows.count) rows.at(i)?.let { row(it) } }
    }
}

@Composable
private fun ListNode(n: PlanNode, modifier: Modifier) {
    val rows = (n.payload as? Payload.Rows)?.rows ?: return
    if (rows.count == 0) {
        n.empty?.let { PlanNodeView(it, modifier) }
        return
    }
    Rows(rows, n.fills, modifier) { PlanNodeView(it) }
}

@Composable
private fun TreeNode(n: PlanNode, modifier: Modifier) {
    val rows = (n.payload as? Payload.TreeRows)?.rows ?: return
    val ctx = LocalViewCtx.current
    Rows(rows, n.fills, modifier) { row -> TreeRowView(row) { toggle(ctx, row) } }
}

private fun toggle(ctx: ViewCtx, row: TreeRow) {
    val open = if (row.id in ctx.ui.expanded) ctx.ui.expanded - row.id else ctx.ui.expanded + row.id
    ctx.setUi(ViewUiState(open, ctx.ui.tabs))
}

@Composable
private fun TreeRowView(row: TreeRow, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Kit.space.l * row.depth), verticalAlignment = Alignment.CenterVertically) {
        if (row.expandable) {
            val icon = if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight
            KitIconButton(icon, stringResource(if (row.expanded) R.string.extview_collapse else R.string.extview_expand), onToggle)
        }
        Box(Modifier.weight(1f)) { PlanNodeView(row.node) }
    }
}

@Composable
private fun TableNode(n: PlanNode, modifier: Modifier) {
    val table = n.payload as? Payload.TableRows ?: return
    if (table.rows.count == 0) {
        n.empty?.let { PlanNodeView(it, modifier) }
        return
    }
    Column(modifier) {
        TableRow(table.columns.map { it.title }, table.columns, header = true)
        Rows(table.rows, n.fills, if (n.fills) Modifier.weight(1f) else Modifier) { cells -> TableRow(cells, table.columns, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<String>, columns: List<PlanColumn>, header: Boolean) {
    val colors = Kit.colors
    Row(
        Modifier.fillMaxWidth().kitGroupSeparator(Kit.space.l).padding(horizontal = Kit.space.l, vertical = Kit.space.s),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.m),
    ) {
        columns.forEachIndexed { i, c ->
            val base = if (header) Kit.type.labelSmall.copy(color = colors.textMuted) else Kit.type.bodyMedium.copy(color = colors.plainText)
            BasicText(cells.getOrElse(i) { "" }, Modifier.weight(c.weight.toFloat()), style = if (c.mono) base.kitMono() else base)
        }
    }
}

// ---- log ---------------------------------------------------------------------------------------------------------

@Composable
private fun LogNode(n: PlanNode, modifier: Modifier) {
    val lines = (n.payload as? Payload.Lines)?.lines.orEmpty()
    val follow = n.flags["follow"] ?: false
    val style = Kit.type.bodySmall.kitMono().copy(color = Kit.colors.plainText)
    if (n.fills) {
        val state = rememberLazyListState()
        FollowEnd(state, lines.size, lines.lastOrNull(), follow)
        LazyColumn(modifier.padding(horizontal = Kit.space.m), state) {
            itemsIndexed(lines) { _, line -> BasicText(line.ifEmpty { " " }, style = style) }
        }
    } else {
        val tail = lines.takeLast(ViewLimits.MAX_INLINE_ROWS)
        Column(modifier.fillMaxWidth().heightIn(max = ExtViewTokens.inlineLogMax).verticalScroll(rememberScrollState())) {
            tail.forEach { BasicText(it.ifEmpty { " " }, style = style) }
        }
    }
}

/** Keeps a followed list at its end while the reader is at it; a reader who scrolled up to read is left alone. */
@Composable
private fun FollowEnd(state: LazyListState, count: Int, last: Any?, follow: Boolean) {
    LaunchedEffect(count, last, follow) {
        if (!follow || count == 0) return@LaunchedEffect
        val visibleEnd = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val atEnd = visibleEnd >= count - 2 - ExtViewTokens.FOLLOW_SLACK
        // Nothing laid out yet is the first frame: start at the end, where the newest line is.
        if (atEnd || state.layoutInfo.totalItemsCount == 0) state.scrollToItem(count - 1)
    }
}

// ---- chat --------------------------------------------------------------------------------------------------------

@Composable
private fun ChatNode(n: PlanNode, modifier: Modifier) {
    val messages = (n.payload as? Payload.Messages)?.messages.orEmpty()
    val follow = n.flags["follow"] ?: true
    val keys = remember(messages.map { it.id }) { uniqueIds(messages.map { it.id }) }
    val description = stringResource(R.string.extchat_list_description, messages.size)
    if (n.fills) {
        val state = rememberLazyListState()
        FollowEnd(state, messages.size, messages.lastOrNull()?.text?.length, follow)
        LazyColumn(
            modifier.semantics { contentDescription = description }, state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Kit.space.m),
            verticalArrangement = Arrangement.spacedBy(Kit.space.s),
        ) {
            itemsIndexed(messages, key = { i, _ -> keys[i] }) { i, m -> Message(m, live = i == messages.lastIndex) }
        }
    } else {
        Column(modifier.fillMaxWidth().padding(Kit.space.m), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            messages.takeLast(ViewLimits.MAX_INLINE_ROWS).forEachIndexed { i, m -> Message(m, live = i == messages.lastIndex) }
        }
    }
}

/** Message ids as lazy keys: a repeated id (bad data) is suffixed so two messages never share a key. */
private fun uniqueIds(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return ids.map { id -> val n = seen.merge(id, 1, Int::plus)!!; if (n == 1) id else "$id~$n" }
}

/**
 * One message. The user's sit at the end in an accent wash, the assistant's at the start on the raised surface, tool
 * calls are cards, system notes are plain and centred. A message that is still arriving says so; one that is done
 * and newest is announced to a screen reader once, not on every appended word.
 */
@Composable
private fun Message(m: ChatMessage, live: Boolean) {
    val colors = Kit.colors
    val mine = m.role == "user"
    val done = m.state != "streaming"
    val fill = when {
        mine -> Tone.Accent.container(colors, colors.background)
        m.state == "error" -> Tone.Danger.container(colors, colors.background)
        else -> colors.raised
    }
    val shape = RoundedCornerShape(Kit.radius.m)
    val announce = if (live && done) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(ExtViewTokens.BUBBLE_MAX_FRACTION).background(fill, shape).border(Kit.hairline, colors.panelBorder, shape)
                .then(announce).padding(Kit.space.m),
            verticalArrangement = Arrangement.spacedBy(Kit.space.xs),
        ) {
            BasicText(roleName(m.role), style = Kit.type.labelSmall.copy(color = colors.textMuted))
            if (m.text.isNotEmpty()) BasicText(m.text, style = Kit.type.bodyMedium.copy(color = colors.plainText))
            m.tool?.let { ToolCard(it) }
            if (!done) KitTag(stringResource(R.string.extchat_streaming), tone = Tone.Accent)
            if (m.state == "error") KitTag(stringResource(R.string.extchat_error), tone = Tone.Danger)
        }
    }
}

@Composable
private fun roleName(role: String): String = stringResource(
    when (role) {
        "user" -> R.string.extchat_you
        "tool" -> R.string.extchat_tool
        "system" -> R.string.extchat_system
        else -> R.string.extchat_assistant
    },
)

@Composable
private fun ToolCard(tool: dev.easyide.extensions.view.ChatTool) {
    val colors = Kit.colors
    Column(verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalAlignment = Alignment.CenterVertically) {
            BasicText(tool.name, style = Kit.type.labelLarge.copy(color = colors.plainText))
            tool.status?.let { KitTag(it, tone = if (it == "failed") Tone.Danger else if (it == "done") Tone.Success else Tone.Neutral) }
        }
        tool.command?.let { BasicText(it, style = Kit.type.bodySmall.kitMono().copy(color = colors.textMuted)) }
        tool.output?.takeIf { it.isNotEmpty() }?.let { CodeBlock(it, Modifier) }
    }
}
