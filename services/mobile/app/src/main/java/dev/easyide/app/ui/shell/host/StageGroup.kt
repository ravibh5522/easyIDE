package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.size
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.SplitAxis
import dev.easyide.app.ui.theme.IconSize

/** One group as the stage draws it: its tabs, where it is among its siblings, and the shape of the window. */
class GroupView(
    val group: EditorGroup,
    val index: Int,
    val active: Boolean,
    val compact: Boolean,
    val split: Boolean,
    val axis: SplitAxis,
)

/** What one group can do, with its own index bound in. [canSplit] is true only where a split fits (the active group, room left). */
class GroupCallbacks(private val stage: StageCallbacks, private val index: Int, val canSplit: Boolean) {
    val onSplit: (() -> Unit)? get() = stage.onSplit.takeIf { canSplit }
    val onUnsplit: (() -> Unit)? get() = stage.onUnsplit?.let { unsplit -> { unsplit(index) } }
    fun activate(uri: DocumentUri) = stage.onActivate(index, uri)
    fun keep(uri: DocumentUri) = stage.onKeep(index, uri)
    fun close(uri: DocumentUri) = stage.onClose(index, uri)
    fun focus() = stage.onFocusGroup(index)
    fun isDirty(uri: DocumentUri) = stage.isDirty(uri)
}

/**
 * One editor group: the tab strip (a wide window) or the document switcher (a phone) over the active
 * document. A touch anywhere in an inactive group focuses it, without being consumed, so the tap still
 * reaches what it landed on.
 */
@Composable
internal fun StageGroup(
    view: GroupView,
    documents: DocumentRegistry,
    renderers: DocumentRendererRegistry,
    idle: PanelRenderer?,
    emptyTitle: String,
    callbacks: GroupCallbacks,
    trailing: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = view.group.activeTab?.uri
    val actions: @Composable RowScope.() -> Unit = {
        trailing()
        callbacks.onSplit?.let { KitIconButton(Icons.Filled.VerticalSplit, stringResource(R.string.wshell_group_split), it) }
        if (view.split) callbacks.onUnsplit?.let { KitIconButton(Icons.Filled.Close, stringResource(R.string.wshell_group_close), it) }
    }
    Column(
        modifier.kitTag("stage-group").groupDivider(view).focusOnTouch(view.active, callbacks::focus).focusGroup(),
    ) {
        val titleOf: @Composable (DocumentUri) -> String = { renderers.titleOf(documents, it) }
        when {
            view.compact -> DocumentSwitcher(view.group, active?.let { titleOf(it) } ?: emptyTitle, titleOf, callbacks, actions)
            view.group.tabs.isNotEmpty() || view.split -> DocumentStrip(
                view.group,
                titleOf = { titleOf(it.uri) },
                isDirty = { callbacks.isDirty(it.uri) },
                onActivate = { callbacks.activate(it.uri) },
                onKeep = { callbacks.keep(it.uri) },
                onClose = { callbacks.close(it.uri) },
                trailing = actions,
            )
        }
        val sides = if (view.compact || view.group.tabs.isNotEmpty() || view.split) WindowInsetsSides.Bottom else WindowInsetsSides.Vertical
        StageBody(active, documents, renderers, idle, { active?.let(callbacks::close) }, Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(sides)))
    }
}

/** A hairline on the edge that faces the previous group. */
@Composable
private fun Modifier.groupDivider(view: GroupView): Modifier {
    if (view.index == 0 || view.compact) return this
    val color = Kit.colors.panelBorder
    val line = Kit.hairline
    val row = view.axis == SplitAxis.ROW
    return drawBehind {
        val w = line.toPx()
        if (row) drawRect(color, Offset.Zero, androidx.compose.ui.geometry.Size(w, size.height))
        else drawRect(color, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, w))
    }
}

/** Reports a touch in an inactive group and leaves it unconsumed. */
private fun Modifier.focusOnTouch(active: Boolean, onFocus: () -> Unit): Modifier =
    if (active) this else pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                onFocus()
            }
        }
    }

/**
 * A phone's replacement for the tab strip (layout-spec.md section 4.1): the active document's title
 * with the count of open ones, which opens the list of them, most recently used first; the active
 * group's actions sit at the end. The list ends with closing the current document.
 */
@Composable
private fun DocumentSwitcher(
    group: EditorGroup,
    title: String,
    titleOf: @Composable (DocumentUri) -> String,
    callbacks: GroupCallbacks,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = Kit.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.wshell_switcher_label, title, group.tabs.size)
    val close = stringResource(R.string.shell_document_close, title)
    val entries = group.mru.map { it to titleOf(it) }
    Row(
        Modifier.background(colors.panel)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .defaultMinSize(minHeight = Kit.metrics.touchFloor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.defaultMinSize(minHeight = Kit.metrics.touchFloor).kitTag("doc-switcher")
                    .kitPressable({ open = group.tabs.isNotEmpty() }, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(horizontal = Kit.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(title, Modifier.weight(1f, fill = false), style = Kit.type.titleSmall.copy(color = colors.plainText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.tabs.size > 1) BasicText(" ${group.tabs.size}", style = Kit.type.labelMedium.copy(color = colors.textMuted))
                if (group.tabs.isNotEmpty()) Image(Icons.Filled.ExpandMore, null, Modifier.size(IconSize.m), colorFilter = ColorFilter.tint(colors.textMuted))
            }
            KitMenu(
                open, { open = false },
                entries.map { (uri, name) ->
                    KitMenuItem.Action(if (callbacks.isDirty(uri)) stringResource(R.string.wshell_switcher_dirty, name) else name, { callbacks.activate(uri) }, checked = uri == group.active)
                } + KitMenuItem.Divider + KitMenuItem.Action(close, { group.active?.let(callbacks::close) }, icon = Icons.Filled.Close),
            )
        }
        actions()
    }
}
