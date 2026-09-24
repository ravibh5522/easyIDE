package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.host.PaneSplitter
import dev.easyide.app.ui.shell.host.PanelRendererRegistry

/** What the bottom panel shows and how tall it is: [heightDp] resolved from [stored] within [availableDp]. */
class BottomPanelSpec(
    val containers: List<ContainerSpec>,
    val active: String?,
    val heightDp: Float,
    val availableDp: Float,
)

/** What the bottom panel asks of the shell: switch container, close, and its size (dp when docked, a detent fraction as a sheet). */
class BottomPanelActions(
    val onSelect: (String) -> Unit,
    val onClose: () -> Unit,
    val onSize: (Float) -> Unit,
    val onReset: () -> Unit,
    val onFocus: (Boolean) -> Unit,
    val onCycle: () -> Unit,
)

/**
 * The bottom panel: container tabs over the active container's view. Docked it has a drag edge on
 * top; as a sheet (a phone) it has a handle that rests on one of three detents when let go and
 * cycles them when tapped (layout-spec.md section 5). Every container that was shown stays composed
 * while another is, and the whole panel stays composed while hidden, so a terminal keeps its
 * renderer and its rows across a hide: it reports no height instead of leaving composition.
 */
@Composable
fun BottomPanel(
    spec: BottomPanelSpec,
    mode: PanelMode,
    visible: Boolean,
    panels: PanelRendererRegistry,
    actions: BottomPanelActions,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // A hidden terminal must not keep the keyboard and swallow typing.
    LaunchedEffect(visible) { if (!visible && hasFocus) focusManager.clearFocus(force = true) }
    val composed = remember { HashSet<String>() }
    spec.active?.let(composed::add)
    val tabs = spec.containers
    val height = dragging ?: spec.heightDp

    Column(
        modifier.keepComposed(visible).height(height.dp).kitTag("bottom-panel").background(Kit.colors.panel)
            .onFocusChanged { hasFocus = it.hasFocus; actions.onFocus(it.hasFocus) },
    ) {
        if (mode == PanelMode.SHEET) {
            SheetHandle(spec, actions, onDrag = { dragging = it })
        } else {
            PaneSplitter(
                spec.heightDp, BottomSizing.dockedLimits(spec.availableDp), BottomSizing.dockedCeiling(spec.availableDp),
                actions.onSize, actions.onReset, vertical = true, growsTowardsStart = true,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KitTabs(tabs.map { it.title }, tabs.indexOfFirst { it.id == spec.active }.coerceAtLeast(0), { actions.onSelect(tabs[it].id) }, Modifier.weight(1f), height = Kit.control.panelTabHeight)
            KitIconButton(Icons.Filled.Close, stringResource(R.string.wshell_bottom_close), actions.onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            composed.forEach { id ->
                val binding = panels.binding(id)
                if (binding != null) key(id) { Box(Modifier.keepComposed(id == spec.active)) { binding.renderer.Render(Modifier) } }
            }
        }
    }
}

/** Composed and measured at full size, but takes no space and draws nothing while [visible] is false. */
private fun Modifier.keepComposed(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (visible) layout(placeable.width, placeable.height) { placeable.place(0, 0) } else layout(placeable.width, 0) {}
}

@Composable
private fun SheetHandle(spec: BottomPanelSpec, actions: BottomPanelActions, onDrag: (Float?) -> Unit) {
    val density = LocalDensity.current.density
    var live by remember { mutableFloatStateOf(spec.heightDp) }
    val label = stringResource(R.string.wshell_bottom_resize)
    val detent = stringResource(
        when (BottomSizing.settle(spec.heightDp, spec.availableDp)) {
            BottomSizing.PEEK -> R.string.wshell_bottom_detent_peek
            BottomSizing.HALF -> R.string.wshell_bottom_detent_half
            else -> R.string.wshell_bottom_detent_full
        },
    )
    Box(
        Modifier.fillMaxWidth().height(Kit.metrics.touchFloor).kitTag("bottom-handle")
            .semantics { contentDescription = label; stateDescription = detent; role = Role.Button }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    live = (live - delta / density).coerceIn(BottomSizing.MIN_USABLE_DP, spec.availableDp)
                    onDrag(live)
                },
                onDragStarted = { live = spec.heightDp },
                onDragStopped = { actions.onSize(BottomSizing.settle(live, spec.availableDp)); onDrag(null) },
            )
            .clickable(onClick = actions.onCycle),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(Kit.space.xxl, Kit.space.xs).background(Kit.colors.textMuted, RoundedCornerShape(Kit.radius.xs)))
    }
}
