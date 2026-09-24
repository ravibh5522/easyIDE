package dev.easyide.app.ui.shell.nav

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.shell.NavItem

/**
 * The destinations that did not fit the bar or rail, in the same order. [KitDialog] is a bottom
 * sheet on a phone and a centred card on wider windows, so the overflow lands where a thumb is.
 */
@Composable
internal fun NavMoreSheet(items: List<NavItem>, active: String?, onSelect: (NavItem) -> Unit, onDismiss: () -> Unit) {
    KitDialog(title = stringResource(R.string.shell_nav_more_title), onDismiss = onDismiss) {
        KitGroup {
            items.forEach { item ->
                KitRow(
                    title = item.title,
                    id = "nav-more-${item.id}",
                    selected = item.id == active,
                    onClick = { onSelect(item) },
                    leading = { Image(NavIcons.icon(item.icon), null, Modifier, colorFilter = ColorFilter.tint(Kit.colors.activityIcon)) },
                )
            }
        }
    }
}
