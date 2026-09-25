package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ShellState

/**
 * Panel and stage side by side, per size class (layout-spec.md sections 3 and 5). On a phone the
 * panel is the first screen and a document is a page pushed over it; on a wider window the primary
 * panel docks at a width the user can drag (a closed panel leaves the stage the whole window).
 * The stage is told which default content the panel's container offers for an empty stage.
 */
@Composable
fun ShellBody(
    state: ShellState,
    containers: ContainerRegistry,
    panels: PanelRendererRegistry,
    onResizePanel: (Float?) -> Unit,
    stage: @Composable (Modifier, PanelRenderer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.current.layout
    val container = containers.active(Placement.SIDEBAR, layout.container(Placement.SIDEBAR), ShellScope.APP)?.id
    val idle = container?.let { panels.binding(it)?.stageDefault }
    when {
        state.compact && !state.pushed -> PanelHost(container, panels, modifier.fillMaxSize())
        state.compact -> stage(modifier.fillMaxSize(), idle)
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val available = maxWidth.value
            Row(Modifier.fillMaxSize()) {
                if (layout.isOpen(Placement.SIDEBAR)) {
                    val width = PanelSizing.width(layout.sizes.explorer, Kit.control.panelWidth.value, available)
                    PanelHost(container, panels, Modifier.width(width.dp).fillMaxHeight())
                    PaneSplitter(width, PanelSizing.limits, PanelSizing.ceiling(available), onResizePanel, { onResizePanel(null) })
                }
                stage(Modifier.weight(1f).fillMaxHeight(), idle)
            }
        }
    }
}
