package dev.easyide.app.ui.shell.nav

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.host.ShellTokens

/** What the surface shows and how; [focus] lands on the active cell when the shell asks for keyboard focus (Back step 5). */
class NavSurfaceState(
    val placement: NavPlacement,
    val items: List<NavItem>,
    val active: String?,
    val pinned: Set<String>,
    val showLabels: Boolean,
    val badges: Map<String, NavBadgeValue>,
    val focus: FocusRequester,
)

/**
 * The navigation surface (shell-model.md section 6): a bottom bar on a phone, a rail elsewhere. Both
 * are the same cells; they differ in axis, in how many fit, and in the edge the active marker sits on.
 * Cells past the capacity collapse into "More".
 */
@Composable
fun NavSurface(surface: NavSurfaceState, onSelect: (NavItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = Kit.colors
    val label = stringResource(R.string.shell_nav_label)
    var moreOpen by remember { mutableStateOf(false) }
    val bottom = surface.placement == NavPlacement.BOTTOM
    val width = Kit.control.railWidth + if (surface.showLabels) ShellTokens.railLabelExtra else 0.dp
    val cellHeight = NavRules.cellHeight(surface.placement, surface.showLabels, Kit.control)
    val sides = if (bottom) {
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    } else {
        WindowInsetsSides.Vertical + if (surface.placement == NavPlacement.RAIL_START) WindowInsetsSides.Start else WindowInsetsSides.End
    }
    val frame = if (bottom) Modifier.fillMaxWidth() else Modifier.fillMaxHeight().width(width)
    BoxWithConstraints(
        modifier.then(frame).background(colors.activityBar).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
            .semantics { contentDescription = label }.focusGroup(),
    ) {
        val capacity = if (bottom) NavRules.barCapacity(maxWidth.value, Kit.metrics.touchFloor.value) else NavRules.railCapacity(maxHeight.value, cellHeight.value)
        val cells = NavRules.cells(surface.items, capacity, surface.pinned, surface.active)
        val landing = if (cells.moreActive) MORE_ID else (cells.shown.firstOrNull { it.id == surface.active } ?: cells.shown.firstOrNull())?.id
        val cell: @Composable (id: String, item: NavItem?, Modifier) -> Unit = { id, item, m ->
            val focusable = if (id == landing) m.focusRequester(surface.focus) else m
            if (item != null) {
                NavCell(
                    NavIcons.of(item.icon), item.title, item.id == surface.active, surface.showLabels, surface.placement,
                    surface.badges[item.id], { onSelect(item) }, focusable, "nav-${item.id}",
                )
            } else {
                NavCell(
                    NavIcons.more, stringResource(R.string.shell_nav_more), cells.moreActive, surface.showLabels, surface.placement,
                    null, { moreOpen = true }, focusable, "nav-more",
                )
            }
        }
        if (bottom) {
            Row(Modifier.fillMaxWidth().height(cellHeight)) {
                cells.shown.forEach { cell(it.id, it, Modifier.weight(1f).fillMaxHeight()) }
                if (cells.hasMore) cell(MORE_ID, null, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                cells.shown.forEach { cell(it.id, it, Modifier.fillMaxWidth().height(cellHeight)) }
                if (cells.hasMore) cell(MORE_ID, null, Modifier.fillMaxWidth().height(cellHeight))
            }
        }
        if (moreOpen) {
            NavMoreSheet(cells.more, surface.active, { moreOpen = false; onSelect(it) }, { moreOpen = false })
        }
    }
}

private const val MORE_ID = "more"
