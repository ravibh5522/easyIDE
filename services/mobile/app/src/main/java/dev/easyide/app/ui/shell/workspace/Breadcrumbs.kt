package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.screens.workspace.lsp.SymbolRow
import dev.easyide.app.ui.shell.host.ShellTokens
import dev.easyide.app.ui.theme.IconSize

/** The folder menu that is open: under the crumb at [anchor], listing [dir]. */
private data class CrumbMenu(val anchor: String, val dir: String)

/**
 * The breadcrumb row under the tabs: `folder > folder > file > symbol` in muted caption text, the last
 * one brightest. A window too narrow for all of it loses the start (the content is laid out at its
 * natural width and pinned to the end), so where you are always shows. A path crumb opens a menu of the
 * files and folders beside it (a folder drills in, a file opens); a symbol crumb jumps to the symbol.
 * [children] lists a folder, [onCrumb] runs when a menu opens so the caller can refresh what it lists.
 */
@Composable
fun Breadcrumbs(
    path: String,
    symbols: List<SymbolRow>,
    children: (dir: String) -> List<DirEntry>,
    onCrumb: () -> Unit,
    onOpenFile: (String) -> Unit,
    onSymbol: (SymbolRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Kit.colors
    var menu by remember { mutableStateOf<CrumbMenu?>(null) }
    // A folder picked in the menu keeps it open on that folder: KitMenu dismisses after every pick, so the pick is noted for its dismissal.
    var drill by remember { mutableStateOf<String?>(null) }
    val crumbs = remember(path) { BreadcrumbModel.path(path) }
    Box(modifier.fillMaxWidth().kitTag("breadcrumbs").background(colors.background).heightIn(min = ShellTokens.breadcrumbHeight).clipToBounds()) {
        Row(Modifier.align(Alignment.CenterStart).keepEndInView().padding(horizontal = Kit.space.s), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, crumb ->
                if (i > 0) Chevron()
                Box {
                    Crumb(crumb.name, bright = crumb.isFile && symbols.isEmpty()) { onCrumb(); menu = CrumbMenu(crumb.path, crumb.parent) }
                    if (menu?.anchor == crumb.path) {
                        KitMenu(
                            expanded = true,
                            onDismiss = { val next = drill; drill = null; menu = next?.let { dir -> menu?.copy(dir = dir) } },
                            items = entryItems(children(menu?.dir.orEmpty()), crumb.path, onOpenFile) { drill = it },
                        )
                    }
                }
            }
            symbols.forEachIndexed { i, row ->
                Chevron()
                Crumb(row.name, bright = i == symbols.lastIndex) { onSymbol(row) }
            }
        }
    }
}

@Composable
private fun entryItems(entries: List<DirEntry>, current: String, onOpenFile: (String) -> Unit, onDrill: (String) -> Unit): List<KitMenuItem> =
    entries.take(ShellTokens.BREADCRUMB_MENU_MAX).map { e ->
        if (e.isDir) KitMenuItem.Action(e.name, { onDrill(e.path) }, icon = iconFor("files"))
        else KitMenuItem.Action(e.name, { onOpenFile(e.path) }, icon = iconFor("file"), checked = if (e.path == current) true else null)
    }

@Composable
private fun Crumb(label: String, bright: Boolean, onClick: () -> Unit) {
    val colors = Kit.colors
    BasicText(
        label,
        Modifier.kitPressable(onClick, role = Role.Button).padding(horizontal = Kit.space.xs),
        style = Kit.text.caption.copy(color = if (bright) colors.plainText else colors.textMuted),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun Chevron() {
    Image(iconFor("chevron_right"), null, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(Kit.colors.textMuted))
}

/** Lays the content out at its natural width, from the start while it fits and pinned to the end once it does not, so the start is what gets clipped. */
private fun Modifier.keepEndInView(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxHeight = constraints.maxHeight))
    val width = minOf(placeable.width, constraints.maxWidth)
    layout(width, placeable.height) { placeable.placeRelative(width - placeable.width, 0) }
}
