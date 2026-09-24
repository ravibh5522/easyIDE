package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.SplitAxis
import dev.easyide.app.ui.shell.Tab

/** One group as the stage draws it: its tabs, where it is among its siblings, and the shape of the window. */
class GroupView(
    val group: EditorGroup,
    val index: Int,
    val active: Boolean,
    val compact: Boolean,
    val groups: Int,
    val axis: SplitAxis,
    /** How many groups the window can show, so "open beside" is only offered where it can split. */
    val capacity: Int,
) {
    val split: Boolean get() = groups > 1
    val hasNext: Boolean get() = index + 1 < groups
}

/** What one group can do, with its own index bound in. [canSplit] is true only where a split fits (the active group, room left). */
class GroupCallbacks(private val stage: StageCallbacks, private val index: Int, val canSplit: Boolean) {
    val onSplit: (() -> Unit)? get() = stage.onSplit.takeIf { canSplit }
    val onUnsplit: (() -> Unit)? get() = stage.onUnsplit?.let { unsplit -> { unsplit(index) } }
    fun activate(uri: DocumentUri) = stage.onActivate(index, uri)
    fun keep(uri: DocumentUri) = stage.onKeep(index, uri)
    fun close(uri: DocumentUri) = stage.onClose(index, uri)
    fun focus() = stage.onFocusGroup(index)
    fun isDirty(uri: DocumentUri) = stage.isDirty(uri)
    val hasTabMenu: Boolean get() = stage.onTabAction != null
    fun tabAction(uri: DocumentUri, action: TabAction) { stage.onTabAction?.invoke(index, uri, action) }
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
        callbacks.onSplit?.let { KitIconButton(iconFor("split"), stringResource(R.string.wshell_group_split), it) }
        if (view.split) callbacks.onUnsplit?.let { KitIconButton(iconFor("close"), stringResource(R.string.wshell_group_close), it) }
    }
    // The tab a press opened a menu for, and where: one menu per group.
    var menu by remember { mutableStateOf<Pair<Tab, IntOffset>?>(null) }
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
                onMenu = if (callbacks.hasTabMenu) ({ tab, at -> menu = tab to at }) else null,
                trailing = actions,
            )
        }
        menu?.let { (tab, at) ->
            val splittable = documents.resolve(tab.uri).supportsSplit
            TabMenu(true, { menu = null }, view.group, tab.key, callbacks, TabMenuContext(splittable && view.capacity > 1, view.hasNext), at)
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
