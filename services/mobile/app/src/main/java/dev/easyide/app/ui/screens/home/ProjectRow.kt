package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * One project on one line: `[icon] name  branch . location ...... [changes] age`. The branch and
 * the location are the muted inline description and give way to the name; the changed-file count
 * and the age are the right-aligned mono facts. A tap opens its page; long-press or a secondary
 * click opens the action menu, which the page's "..." button also offers.
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
    val git = item.meta?.git
    val age = relativeAge(nowMs, item.project.lastOpenedAtEpochMs).text()

    Box(modifier) {
        KitRow(
            title = item.project.name,
            subtitle = joinParts(git?.branch, item.locationLabel()),
            leading = { ProjectGlyph(item.project.name) },
            selected = selected,
            onClick = onClick,
            id = HomeMetrics.PROJECT_ROW_ID,
            modifier = Modifier.contextMenuTrigger { menuOpen = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                    if (git != null && git.isDirty) KitTag(git.changedFiles.toString(), tone = Tone.Warning)
                    MonoText(age)
                }
            },
        )
        KitMenu(menuOpen, { menuOpen = false }, projectMenuItems(actions))
    }
}
