package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitRow

/**
 * One project: its name, where its files live, and on the right the mono facts (branch with a
 * `*` when dirty, and how long ago it was opened). A tap opens its page; long-press or a
 * secondary click opens the action menu, which the page's "..." button also offers.
 */
@Composable
internal fun ProjectRow(
    item: ProjectListItem,
    nowMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
    actions: ProjectMenuActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val branch = item.meta?.git?.branchLabel()
    val age = relativeAge(nowMs, item.project.lastOpenedAtEpochMs).text()

    Box(modifier) {
        KitRow(
            title = item.project.name,
            subtitle = item.locationLabel(),
            selected = selected,
            onClick = onClick,
            id = HomeMetrics.PROJECT_ROW_ID,
            modifier = Modifier.contextMenuTrigger { menuOpen = true },
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    if (branch != null) MonoText(branch)
                    MonoText(age)
                }
            },
        )
        KitMenu(menuOpen, { menuOpen = false }, projectMenuItems(actions))
    }
}
