package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.layout.HingeSplit
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.PaneBounds
import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.PanelLayout
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellLimits
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.shell.host.DocumentRendererRegistry
import dev.easyide.app.ui.shell.host.PanelHost
import dev.easyide.app.ui.shell.host.PanelRenderer
import dev.easyide.app.ui.shell.host.PanelRendererRegistry
import dev.easyide.app.ui.shell.host.PanelSizing
import dev.easyide.app.ui.shell.host.StageCallbacks
import dev.easyide.app.ui.shell.host.StageHost

/** The registries and renderers the workspace body draws with. */
class WorkspaceParts(
    val documents: DocumentRegistry,
    val containers: ContainerRegistry,
    val panels: PanelRendererRegistry,
    val renderers: DocumentRendererRegistry,
)

/** What the screen contributes to the body: the stage's welcome page and actions, the notice above it, and where terminal focus goes. */
class WorkspaceSlots(
    val idle: PanelRenderer,
    val emptyTitle: String,
    val trailing: @Composable RowScope.() -> Unit,
    val notice: @Composable () -> Unit,
    val onTerminalFocus: (Boolean) -> Unit,
)

/**
 * The workspace's panels and stage laid out for the window (layout-spec.md section 4): docked
 * panels and a resizable bottom panel where they fit, side sheets with a scrim and a bottom panel
 * that rests on detents on a phone, the two pages of a book posture, the stage above the hinge in
 * tabletop. Which arrangement and which panels are open is the shell state's; how they are drawn is
 * [WorkspaceLayout]'s.
 */
@Composable
fun WorkspaceBody(
    state: ShellState,
    model: WorkspaceShellModel,
    parts: WorkspaceParts,
    slots: WorkspaceSlots,
    stageCallbacks: StageCallbacks,
    modifier: Modifier = Modifier,
) {
    val layout = state.current.layout
    val plan = WorkspaceLayout.plan(state.window, layout)
    val view = BodyView(state, layout, plan, model, parts, slots, stageCallbacks)
    Box(modifier.fillMaxSize()) {
        when (plan.arrangement) {
            PaneArrangement.BOOK -> BookLayout(view)
            else -> FlatLayout(view)
        }
        SideSheet(plan.sidebar == PanelMode.SHEET, false, view.containerIn(Placement.SIDEBAR), parts.panels, { model.toggle(Placement.SIDEBAR) })
        SideSheet(plan.secondary == PanelMode.SHEET, true, view.containerIn(Placement.SECONDARY_SIDEBAR), parts.panels, { model.toggle(Placement.SECONDARY_SIDEBAR) })
    }
}

/** Everything one arrangement needs, resolved once. */
private class BodyView(
    val state: ShellState,
    val layout: PanelLayout,
    val plan: LayoutPlan,
    val model: WorkspaceShellModel,
    val parts: WorkspaceParts,
    val slots: WorkspaceSlots,
    val stageCallbacks: StageCallbacks,
) {
    /** The container a placement shows: the layout's choice, else the first one available there. */
    fun containerIn(p: Placement): String? = parts.containers.active(p, layout.container(p), ShellScope.WORKSPACE)?.id

    val bottomIsSheet: Boolean get() = Placement.PANEL in ShellLimits.overlays(state.window)
}

/** Compact, medium and expanded: [sidebar | stage over bottom panel | secondary]. */
@Composable
private fun FlatLayout(v: BodyView) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxWidth.value
        Row(Modifier.fillMaxSize()) {
            if (v.plan.sidebar == PanelMode.DOCKED) {
                DockedSidePanel(v.containerIn(Placement.SIDEBAR), v.parts.panels, sideSizing(v, Placement.SIDEBAR, available), atEnd = false)
            }
            Center(v, Modifier.weight(1f).fillMaxHeight())
            if (v.plan.secondary == PanelMode.DOCKED) {
                DockedSidePanel(v.containerIn(Placement.SECONDARY_SIDEBAR), v.parts.panels, sideSizing(v, Placement.SECONDARY_SIDEBAR, available), atEnd = true)
            }
        }
    }
}

/**
 * Book posture: the panels stacked on the page left of the hinge, the stage on the page right of it
 * (layout-spec.md section 4.5). The pages snap to the hinge, so nothing draws across it; before the
 * first layout, or when the fold does not cross the body, it is laid out flat.
 */
@Composable
private fun BookLayout(v: BodyView) {
    val fold = v.state.window.fold
    var frame by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val split = frame?.let { f -> fold?.let { HingeSplit.around(it.spanStart, it.spanEnd, it.occludes, f.left.toInt(), f.width.toInt()) } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { frame = it.boundsInWindow() }) {
        if (split == null) {
            FlatLayout(v)
            return@Box
        }
        val density = LocalDensity.current
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.width(with(density) { split.first.toDp() }).fillMaxHeight()) {
                val available = maxHeight.value
                Column(Modifier.fillMaxSize()) {
                    PanelHost(v.containerIn(Placement.SIDEBAR), v.parts.panels, Modifier.weight(1f).fillMaxWidth())
                    BottomHost(v, available, hinge = null)
                }
            }
            Spacer(Modifier.width(with(density) { split.gap.toDp() }))
            Stage(v, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** The stage over the bottom panel; in tabletop the hinge sets where one ends and the other begins. */
@Composable
private fun Center(v: BodyView, modifier: Modifier) {
    val fold = v.state.window.fold
    var frame by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val tabletop = v.plan.arrangement == PaneArrangement.TABLETOP
    val hinge = if (tabletop) frame?.let { f -> fold?.let { HingeSplit.around(it.spanStart, it.spanEnd, it.occludes, f.top.toInt(), f.height.toInt()) } } else null
    BoxWithConstraints(modifier.onGloballyPositioned { frame = it.boundsInWindow() }) {
        val density = LocalDensity.current
        val available = maxHeight.value
        Column(Modifier.fillMaxSize()) {
            Stage(v, if (hinge != null) Modifier.height(with(density) { hinge.first.toDp() }).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth())
            if (hinge != null) Spacer(Modifier.height(with(density) { hinge.gap.toDp() }))
            BottomHost(v, available, hinge?.let { with(density) { it.second.toDp().value } })
        }
    }
}

/** The stage: the language servers' install notice above the groups. */
@Composable
private fun Stage(v: BodyView, modifier: Modifier) {
    Column(modifier) {
        v.slots.notice()
        StageHost(v.state, v.parts.documents, v.parts.renderers, v.stageCallbacks, Modifier.weight(1f), v.slots.idle, v.slots.emptyTitle, v.slots.trailing)
    }
}

/**
 * The bottom panel, composed from the first time it opens and kept after: see [BottomPanel]. [hinge] is
 * the height the fold leaves for it in tabletop; otherwise its height is the sheet's detent or the
 * docked size, both resolved against [availableDp], the height of the column it shares with the stage.
 */
@Composable
private fun BottomHost(v: BodyView, availableDp: Float, hinge: Float?) {
    val open = v.plan.bottom != PanelMode.HIDDEN
    var used by remember { mutableStateOf(open) }
    LaunchedEffect(open) { if (open) used = true }
    if (!used) return
    val stored = v.layout.sizes.bottom
    val sheet = v.bottomIsSheet
    val model = v.model
    val tabs = v.parts.containers.inPlacement(Placement.PANEL, ShellScope.WORKSPACE).filter { v.parts.panels.binding(it.id) != null }
    val active = v.containerIn(Placement.PANEL)
    val height = hinge ?: if (sheet) BottomSizing.sheetHeight(stored, availableDp) else BottomSizing.dockedHeight(stored, availableDp)
    BottomPanel(
        BottomPanelSpec(tabs, active, height, availableDp),
        if (sheet) PanelMode.SHEET else PanelMode.DOCKED,
        open,
        v.parts.panels,
        BottomPanelActions(
            onSelect = model::reveal,
            onClose = { model.toggle(Placement.PANEL) },
            onSize = { model.resizePane(Pane.BOTTOM, it) },
            onReset = { model.resizePane(Pane.BOTTOM, null) },
            onFocus = { v.slots.onTerminalFocus(it && active == dev.easyide.app.ui.shell.CoreShell.TERMINAL) },
            onCycle = { model.resizePane(Pane.BOTTOM, BottomSizing.nextFraction(stored)) },
        ),
        Modifier.fillMaxWidth(),
    )
}

/** A side panel's width from what was dragged, else the panel default, and what dragging it does. */
@Composable
private fun sideSizing(v: BodyView, placement: Placement, availableDp: Float): SideSizing {
    val secondary = placement == Placement.SECONDARY_SIDEBAR
    val limits = if (secondary) PaneBounds.right else PanelSizing.limits
    val pane = if (secondary) Pane.RIGHT else Pane.EXPLORER
    val stored = if (secondary) v.layout.sizes.right else v.layout.sizes.explorer
    return SideSizing(
        widthDp = PanelSizing.width(stored, Kit.control.panelWidth.value, availableDp, limits),
        limits = limits,
        ceilingDp = PanelSizing.ceiling(availableDp, limits),
        onSize = { v.model.resizePane(pane, it) },
        onReset = { v.model.resizePane(pane, null) },
    )
}
