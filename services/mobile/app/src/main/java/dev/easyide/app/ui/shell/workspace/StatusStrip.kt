package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.host.ShellTokens
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.tabular
import kotlin.math.roundToInt

/** What the status strip shows: the built-in facts, and the composables of the other features' items. */
class StatusParts(
    val project: String,
    /** The active file's name and line count, or null when the active document is not a file. */
    val file: String?,
    val lines: Int?,
    val dirty: Boolean,
    val onSave: () -> Unit,
    val extLeft: @Composable () -> Unit,
    val extRight: @Composable () -> Unit,
    /** Diagnostic counts and the language server's status, which open Problems. */
    val problems: @Composable RowScope.() -> Unit,
)

private val LEFT = listOf(StatusId.PROJECT, StatusId.FILE, StatusId.LINES, StatusId.EXT_LEFT)
private val RIGHT = listOf(StatusId.PROBLEMS, StatusId.EXT_RIGHT, StatusId.SAVE)
private val SQUEEZABLE = setOf(StatusId.PROJECT, StatusId.FILE)

/**
 * The status strip (layout-spec.md sections 4.1 and 5): the parts left of the middle and right of it,
 * fitted to the width by [StatusPlan]. A part that does not fit is left out whole and a name is
 * truncated first, so nothing ever overlaps; widths come from measuring, not from a guess at the text.
 */
@Composable
fun StatusStrip(parts: StatusParts, width: WidthClass, modifier: Modifier = Modifier) {
    val colors = Kit.colors
    val allowed = StatusPlan.allowed(width)
    val density = LocalDensity.current
    val gap = with(density) { Kit.space.l.roundToPx() }
    val nameMin = with(density) { ShellTokens.statusNameMin.roundToPx() }
    val text = Kit.text.caption.tabular().copy(color = colors.statusBarText)
    Layout(
        content = {
            if (StatusId.PROJECT in allowed) Slot(StatusId.PROJECT) { Named(iconFor("folder_copy"), parts.project, text) }
            if (StatusId.FILE in allowed && parts.file != null) Slot(StatusId.FILE) { Named(iconFor("file"), parts.file + if (parts.dirty) DIRTY_MARK else "", text) }
            if (StatusId.LINES in allowed && parts.lines != null) Slot(StatusId.LINES) { BasicText(stringResource(R.string.wshell_status_lines, parts.lines), style = text, maxLines = 1) }
            if (StatusId.EXT_LEFT in allowed) Slot(StatusId.EXT_LEFT) { parts.extLeft() }
            if (StatusId.PROBLEMS in allowed) Slot(StatusId.PROBLEMS) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) { parts.problems(this) } }
            if (StatusId.EXT_RIGHT in allowed) Slot(StatusId.EXT_RIGHT) { parts.extRight() }
            if (StatusId.SAVE in allowed && parts.dirty) Slot(StatusId.SAVE) { SaveButton(parts.onSave, text) }
        },
        modifier = modifier.fillMaxWidth().kitTag("status-strip").background(colors.statusBar).height(Kit.control.statusHeight).padding(horizontal = Kit.control.hPad),
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val slots = measurables.map { m ->
            val id = m.layoutId as StatusId
            val natural = m.maxIntrinsicWidth(height)
            StatusSlot(id, natural.toFloat(), (if (id in SQUEEZABLE) minOf(natural, nameMin) else natural).toFloat())
        }.filter { it.width > 0f }
        val widths = StatusPlan.fit(slots, constraints.maxWidth.toFloat(), gap.toFloat())
        val placed = measurables.filter { widths.containsKey(it.layoutId) }.associate { m ->
            val id = m.layoutId as StatusId
            id to m.measure(Constraints(maxWidth = widths.getValue(id).roundToInt(), maxHeight = height))
        }
        layout(constraints.maxWidth, height) {
            var start = 0
            LEFT.forEach { id -> placed[id]?.let { it.placeRelative(start, (height - it.height) / 2); start += it.width + gap } }
            var end = constraints.maxWidth
            RIGHT.asReversed().forEach { id -> placed[id]?.let { end -= it.width; it.placeRelative(end, (height - it.height) / 2); end -= gap } }
        }
    }
}

@Composable
private fun Slot(id: StatusId, content: @Composable () -> Unit) {
    Box(Modifier.layoutId(id), contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
private fun Named(icon: ImageVector, name: String, text: androidx.compose.ui.text.TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        Image(icon, null, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(Kit.colors.statusBarText))
        BasicText(name, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SaveButton(onSave: () -> Unit, text: androidx.compose.ui.text.TextStyle) {
    val label = stringResource(R.string.command_save)
    Row(Modifier.clickable(onClick = onSave), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        Image(iconFor("save"), label, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(Kit.colors.statusBarText))
        BasicText(label, style = text, maxLines = 1)
    }
}

private const val DIRTY_MARK = " *"
